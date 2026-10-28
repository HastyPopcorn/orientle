package orientle;

// Sphere maths

public final class EarthMaths {

    /** IUGG mean Earth radius. */
    public static final double EARTH_RADIUS_KM = 6371.0088;
    public static final double KM_PER_MILE = 1.609344;

    private EarthMaths() {}

    /** Great-circle (haversine) distance between two points, in kilometres. */
    public static double distanceKm(GeoPoint a, GeoPoint b) {
        double phi1 = Math.toRadians(a.lat());
        double phi2 = Math.toRadians(b.lat());
        double dPhi = phi2 - phi1;
        double dLambda = Math.toRadians(b.lon() - a.lon());

        double sinHalfPhi = Math.sin(dPhi / 2);
        double sinHalfLambda = Math.sin(dLambda / 2);
        double h = sinHalfPhi * sinHalfPhi
                + Math.cos(phi1) * Math.cos(phi2) * sinHalfLambda * sinHalfLambda;
        h = Math.min(1.0, Math.max(0.0, h)); // guard against rounding just outside [0,1]
        return 2 * EARTH_RADIUS_KM * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
    }

    /**
     * Initial compass bearing (forward azimuth) when travelling the shortest
     * path from {@code from} to {@code to}, in degrees clockwise from true
     * north, in [0, 360).
     */
    public static double initialBearingDeg(GeoPoint from, GeoPoint to) {
        double phi1 = Math.toRadians(from.lat());
        double phi2 = Math.toRadians(to.lat());
        double dLambda = Math.toRadians(to.lon() - from.lon());

        double y = Math.sin(dLambda) * Math.cos(phi2);
        double x = Math.cos(phi1) * Math.sin(phi2)
                - Math.sin(phi1) * Math.cos(phi2) * Math.cos(dLambda);
        return normalizeDegrees(Math.toDegrees(Math.atan2(y, x)));
    }

    /** The point reached by travelling {@code distanceKm} from {@code from} on the given initial bearing. */
    public static GeoPoint destination(GeoPoint from, double bearingDeg, double distanceKm) {
        double delta = distanceKm / EARTH_RADIUS_KM;
        double theta = Math.toRadians(bearingDeg);
        double phi1 = Math.toRadians(from.lat());
        double lambda1 = Math.toRadians(from.lon());

        double sinPhi2 = Math.sin(phi1) * Math.cos(delta)
                + Math.cos(phi1) * Math.sin(delta) * Math.cos(theta);
        sinPhi2 = Math.min(1.0, Math.max(-1.0, sinPhi2));
        double phi2 = Math.asin(sinPhi2);
        double lambda2 = lambda1 + Math.atan2(
                Math.sin(theta) * Math.sin(delta) * Math.cos(phi1),
                Math.cos(delta) - Math.sin(phi1) * sinPhi2);

        double lat = Math.min(90.0, Math.max(-90.0, Math.toDegrees(phi2)));
        double lon = ((Math.toDegrees(lambda2) + 180) % 360 + 360) % 360 - 180; // [-180, 180)
        return new GeoPoint(lat, lon);
    }

    /** Wraps an angle into [0, 360). */
    public static double normalizeDegrees(double degrees) {
        double r = degrees % 360;
        if (r < 0) {
            r += 360;
        }
        // e.g. -1e-15 + 360 rounds to exactly 360.0
        return r >= 360 ? 0 : r;
    }

    /**
     * Shortest signed rotation from {@code fromDeg} to {@code toDeg}, in
     * [-180, 180). Positive means {@code toDeg} is clockwise of {@code fromDeg}.
     * (359 -> 1 is +2, not -358.)
     */
    public static double signedAngleDifferenceDeg(double fromDeg, double toDeg) {
        return ((toDeg - fromDeg) % 360 + 540) % 360 - 180;
    }
}