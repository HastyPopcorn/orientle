package orientle.server;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import orientle.Config;
import orientle.game.Game;
import orientle.game.GameService;
import orientle.geo.Geodesy;
import orientle.json.Json;

/**
 * HTTP API (JSON in, JSON out). Every game endpoint returns the same "game state" shape.
 *
 * <pre>
 *   POST /api/games              {"rounds": 5}                         -> 201 game state (rounds optional)
 *   GET  /api/games/{id}                                               -> game state (use to resume)
 *   POST /api/games/{id}/guess   {"bearing": 288, "distance": 5500,
 *                                 "unit": "km"|"mi"}                   -> game state incl. the new result
 *   POST /api/games/{id}/next                                          -> game state for the next round
 * </pre>
 *
 * Anything outside {@code /api/} is served from the static directory.
 */
public final class OrientleServer {

    private static final Logger LOG = Logger.getLogger(OrientleServer.class.getName());

    static final int DEFAULT_ROUNDS = 5;
    static final int MAX_ROUNDS = 20;
    /** Anything beyond this is not a plausible distance on Earth (circumference is ~40,075 km). */
    static final double MAX_GUESS_KM = 50_000;
    private static final int MAX_BODY_BYTES = 4096;
    private static final Pattern GAME_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final HttpServer http;
    private final GameService games;
    private final StaticFiles staticFiles;
    private final String corsOrigin;
    private final ExecutorService executor;

    /** Pass port 0 in {@code config} to bind an ephemeral port (see {@link #port()}). */
    public OrientleServer(Config config, GameService games) throws IOException {
        this.games = games;
        this.staticFiles = new StaticFiles(config.staticDir());
        this.corsOrigin = config.corsOrigin();
        this.http = HttpServer.create(new InetSocketAddress(config.port()), 0);
        this.executor = Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors() * 2));
        http.setExecutor(executor);
        http.createContext("/api/", this::handleApi);
        http.createContext("/", this::handleStatic);
    }

    public void start() {
        http.start();
    }

    public void stop() {
        http.stop(0);
        executor.shutdownNow();
    }

    public int port() {
        return http.getAddress().getPort();
    }

    public boolean staticEnabled() {
        return staticFiles.enabled();
    }

    // ------------------------------------------------------------------ static

    private void handleStatic(HttpExchange ex) throws IOException {
        try {
            staticFiles.handle(ex);
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Unhandled error serving " + ex.getRequestURI(), e);
            try {
                ex.sendResponseHeaders(500, -1);
            } catch (IOException ignored) {
                // headers may already have been sent
            }
        } finally {
            ex.close();
        }
    }

    // --------------------------------------------------------------------- API

    private record Reply(int status, Object body) {}

    private void handleApi(HttpExchange ex) throws IOException {
        try {
            addCors(ex);
            if (ex.getRequestMethod().equals("OPTIONS")) {
                ex.sendResponseHeaders(204, -1);
                return;
            }
            Reply reply = route(ex);
            send(ex, reply.status(), reply.body());
        } catch (ApiException e) {
            if (e.allow != null) {
                ex.getResponseHeaders().set("Allow", e.allow);
            }
            send(ex, e.status, error(e.code, e.getMessage()));
        } catch (GameService.GameNotFoundException e) {
            send(ex, 404, error("NOT_FOUND", "Game not found (it may have expired)"));
        } catch (Game.WrongPhaseException e) {
            send(ex, 409, error("WRONG_PHASE", e.getMessage()));
        } catch (GameService.TooManyGamesException e) {
            send(ex, 503, error("BUSY", e.getMessage()));
        } catch (Json.JsonException e) {
            send(ex, 400, error("BAD_REQUEST", e.getMessage()));
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Unhandled error handling " + ex.getRequestMethod() + " " + ex.getRequestURI(), e);
            send(ex, 500, error("INTERNAL_ERROR", "Something went wrong"));
        } finally {
            ex.close();
        }
    }

    private Reply route(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String rest = ex.getRequestURI().getPath().substring("/api/".length());
        String[] seg = rest.split("/");

        if (seg.length == 1 && seg[0].equals("games")) {
            requireMethod(method, "POST");
            return new Reply(201, createGame(ex));
        }
        if (seg.length >= 2 && seg.length <= 3 && seg[0].equals("games")) {
            if (!GAME_ID.matcher(seg[1]).matches()) {
                throw ApiException.notFound("Game not found");
            }
            if (seg.length == 2) {
                requireMethod(method, "GET");
                return new Reply(200, Views.gameState(games.get(seg[1]).snapshot()));
            }
            if (seg[2].equals("guess")) {
                requireMethod(method, "POST");
                return new Reply(200, submitGuess(ex, seg[1]));
            }
            if (seg[2].equals("next")) {
                requireMethod(method, "POST");
                Game game = games.get(seg[1]);
                game.next();
                return new Reply(200, Views.gameState(game.snapshot()));
            }
        }
        throw ApiException.notFound("No such endpoint");
    }

    private Object createGame(HttpExchange ex) throws IOException {
        Map<String, Object> body = readJsonObject(ex, true);
        int rounds = DEFAULT_ROUNDS;
        if (body.containsKey("rounds")) {
            double d = number(body, "rounds");
            if (d != Math.rint(d) || d < 1 || d > MAX_ROUNDS) {
                throw ApiException.badRequest("'rounds' must be a whole number between 1 and " + MAX_ROUNDS);
            }
            rounds = (int) d;
        }
        // Can't build more rounds than there are distinct panoramas.
        Game game = games.create(Math.min(rounds, games.maxRounds()));
        return Views.gameState(game.snapshot());
    }

    private Object submitGuess(HttpExchange ex, String gameId) throws IOException {
        Game game = games.get(gameId);
        Map<String, Object> body = readJsonObject(ex, false);

        double bearing = number(body, "bearing");
        if (bearing < 0 || bearing > 360) {
            throw ApiException.badRequest("'bearing' must be between 0 and 360 (degrees clockwise from north)");
        }
        bearing = Geodesy.normalizeDegrees(bearing); // 360 is the same direction as 0

        double distance = number(body, "distance");
        if (!(distance > 0)) {
            throw ApiException.badRequest("'distance' must be greater than 0");
        }
        Object unit = body.get("unit");
        double distanceKm;
        if (unit == null || "km".equalsIgnoreCase(String.valueOf(unit))) {
            distanceKm = distance;
        } else if ("mi".equalsIgnoreCase(String.valueOf(unit))) {
            distanceKm = distance * Geodesy.KM_PER_MILE;
        } else {
            throw ApiException.badRequest("'unit' must be \"km\" or \"mi\"");
        }
        if (distanceKm > MAX_GUESS_KM) {
            throw ApiException.badRequest("'distance' is larger than any distance on Earth");
        }

        game.guess(bearing, distanceKm);
        return Views.gameState(game.snapshot());
    }

    // ------------------------------------------------------------------ helpers

    private static void requireMethod(String actual, String allowed) {
        if (!actual.equals(allowed)) {
            throw ApiException.methodNotAllowed(allowed);
        }
    }

    private static double number(Map<String, Object> body, String key) {
        if (!(body.get(key) instanceof Double d)) {
            throw ApiException.badRequest("'" + key + "' must be a number");
        }
        return d;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readJsonObject(HttpExchange ex, boolean emptyAllowed) throws IOException {
        byte[] raw = ex.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (raw.length > MAX_BODY_BYTES) {
            throw new ApiException(413, "BODY_TOO_LARGE", "Request body too large", null);
        }
        String text = new String(raw, StandardCharsets.UTF_8);
        if (text.isBlank()) {
            if (emptyAllowed) {
                return new LinkedHashMap<>();
            }
            throw ApiException.badRequest("A JSON body is required");
        }
        Object parsed = Json.parse(text);
        if (!(parsed instanceof Map<?, ?>)) {
            throw ApiException.badRequest("The request body must be a JSON object");
        }
        return (Map<String, Object>) parsed;
    }

    private static Map<String, Object> error(String code, String message) {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("code", code);
        inner.put("message", message);
        return Map.of("error", inner);
    }

    private void addCors(HttpExchange ex) {
        if (corsOrigin != null) {
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", corsOrigin);
            ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
            if (!corsOrigin.equals("*")) {
                ex.getResponseHeaders().set("Vary", "Origin");
            }
        }
    }

    private static void send(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = Json.stringify(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}