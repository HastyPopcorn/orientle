package orientle;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small, strict JSON reader/writer so the project needs no dependencies.
 */
public final class Json {

    private static final int MAX_DEPTH = 64;

    private Json() {}

    /** Thrown for malformed JSON input. */
    public static final class JsonException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public JsonException(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------------ parsing

    public static Object parse(String text) {
        return new Parser(text).parseDocument();
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        Object parseDocument() {
            skipWhitespace();
            Object value = parseValue(0);
            skipWhitespace();
            if (pos != s.length()) {
                throw error("unexpected content after JSON value");
            }
            return value;
        }

        private Object parseValue(int depth) {
            if (depth > MAX_DEPTH) {
                throw error("nesting too deep");
            }
            if (pos >= s.length()) {
                throw error("unexpected end of input");
            }
            char c = s.charAt(pos);
            switch (c) {
                case '{':
                    return parseObject(depth);
                case '[':
                    return parseArray(depth);
                case '"':
                    return parseString();
                case 't':
                    expectLiteral("true");
                    return Boolean.TRUE;
                case 'f':
                    expectLiteral("false");
                    return Boolean.FALSE;
                case 'n':
                    expectLiteral("null");
                    return null;
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        return parseNumber();
                    }
                    throw error("unexpected character '" + c + "'");
            }
        }

        private Map<String, Object> parseObject(int depth) {
            pos++; // '{'
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                if (peek() != '"') {
                    throw error("expected a string key");
                }
                String key = parseString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                map.put(key, parseValue(depth + 1)); // duplicate keys: last one wins
                skipWhitespace();
                char c = next();
                if (c == '}') {
                    return map;
                }
                if (c != ',') {
                    pos--;
                    throw error("expected ',' or '}'");
                }
            }
        }

        private List<Object> parseArray(int depth) {
            pos++; // '['
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                skipWhitespace();
                list.add(parseValue(depth + 1));
                skipWhitespace();
                char c = next();
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    pos--;
                    throw error("expected ',' or ']'");
                }
            }
        }

        private String parseString() {
            pos++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= s.length()) {
                    throw error("unterminated string");
                }
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c < 0x20) {
                    pos--;
                    throw error("unescaped control character in string");
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (pos >= s.length()) {
                    throw error("unterminated escape sequence");
                }
                char e = s.charAt(pos++);
                switch (e) {
                    case '"':
                    case '\\':
                    case '/':
                        sb.append(e);
                        break;
                    case 'b':
                        sb.append('\b');
                        break;
                    case 'f':
                        sb.append('\f');
                        break;
                    case 'n':
                        sb.append('\n');
                        break;
                    case 'r':
                        sb.append('\r');
                        break;
                    case 't':
                        sb.append('\t');
                        break;
                    case 'u':
                        if (pos + 4 > s.length()) {
                            throw error("incomplete \\u escape");
                        }
                        int code = 0;
                        for (int i = 0; i < 4; i++) {
                            int digit = Character.digit(s.charAt(pos + i), 16);
                            if (digit < 0) {
                                throw error("invalid \\u escape");
                            }
                            code = code * 16 + digit;
                        }
                        pos += 4;
                        sb.append((char) code);
                        break;
                    default:
                        pos--;
                        throw error("invalid escape '\\" + e + "'");
                }
            }
        }

        /** JSON number grammar: -? (0 | [1-9][0-9]*) (. [0-9]+)? ([eE] [+-]? [0-9]+)? */
        private Double parseNumber() {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            if (peek() == '0') {
                pos++;
            } else if (peek() >= '1' && peek() <= '9') {
                skipDigits();
            } else {
                throw error("invalid number");
            }
            if (peek() == '.') {
                pos++;
                if (!isDigit(peek())) {
                    throw error("digits required after decimal point");
                }
                skipDigits();
            }
            if (peek() == 'e' || peek() == 'E') {
                pos++;
                if (peek() == '+' || peek() == '-') {
                    pos++;
                }
                if (!isDigit(peek())) {
                    throw error("digits required in exponent");
                }
                skipDigits();
            }
            double d = Double.parseDouble(s.substring(start, pos));
            if (Double.isInfinite(d)) {
                pos = start;
                throw error("number out of range");
            }
            return d;
        }

        private void skipDigits() {
            while (isDigit(peek())) {
                pos++;
            }
        }

        private static boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }

        private void expectLiteral(String literal) {
            if (!s.startsWith(literal, pos)) {
                throw error("unexpected token");
            }
            pos += literal.length();
        }

        private void expect(char c) {
            if (peek() != c) {
                throw error("expected '" + c + "'");
            }
            pos++;
        }

        private void skipWhitespace() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        /** Current character, or NUL at end of input. */
        private char peek() {
            return pos < s.length() ? s.charAt(pos) : '\0';
        }

        private char next() {
            if (pos >= s.length()) {
                throw error("unexpected end of input");
            }
            return s.charAt(pos++);
        }

        private JsonException error(String message) {
            return new JsonException("Invalid JSON at position " + pos + ": " + message);
        }
    }

    // ------------------------------------------------------------------ writing

    public static String stringify(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String str) {
            writeString(sb, str);
        } else if (v instanceof Boolean b) {
            sb.append(b ? "true" : "false");
        } else if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (!Double.isFinite(d)) {
                throw new IllegalArgumentException("JSON cannot represent " + d);
            }
            // Plain notation without a trailing ".0": 100.0 -> 100, 5570.25 -> 5570.25
            sb.append(BigDecimal.valueOf(d).stripTrailingZeros().toPlainString());
        } else if (v instanceof Number n) {
            sb.append(n);
        } else if (v instanceof Enum<?> e) {
            writeString(sb, e.name());
        } else if (v instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, String.valueOf(entry.getKey()));
                sb.append(':');
                write(sb, entry.getValue());
            }
            sb.append('}');
        } else if (v instanceof Iterable<?> iterable) {
            sb.append('[');
            boolean first = true;
            for (Object item : iterable) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                write(sb, item);
            }
            sb.append(']');
        } else {
            throw new IllegalArgumentException("Cannot serialise " + v.getClass().getName());
        }
    }

    private static void writeString(StringBuilder sb, String str) {
        sb.append('"');
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }
}