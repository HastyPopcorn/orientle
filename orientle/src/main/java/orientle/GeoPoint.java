package orientle;

/** A latitude/longitude pair in decimal degrees. */
public record GeoPoint(double lat, double lon) {

    public GeoPoint {
        // Written as !(x >= a && x <= b) so that NaN is rejected too.
        if (!(lat >= -90 && lat <= 90)) {
            throw new IllegalArgumentException("lat must be between -90 and 90, got " + lat);
        }
        if (!(lon >= -180 && lon <= 180)) {
            throw new IllegalArgumentException("lon must be between -180 and 180, got " + lon);
        }
    }
}