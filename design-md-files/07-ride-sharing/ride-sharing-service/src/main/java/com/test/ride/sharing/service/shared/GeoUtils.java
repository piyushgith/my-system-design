package com.test.ride.sharing.service.shared;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class GeoUtils {

    private static final double EARTH_RADIUS_KM = 6371.0;

    private GeoUtils() {
    }

    public static double distanceKm(GeoPoint a, GeoPoint b) {
        double lat1 = a.getLat().doubleValue();
        double lng1 = a.getLng().doubleValue();
        double lat2 = b.getLat().doubleValue();
        double lng2 = b.getLng().doubleValue();

        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double sinLat = Math.sin(dLat / 2);
        double sinLng = Math.sin(dLng / 2);
        double haversine = sinLat * sinLat
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * sinLng * sinLng;
        return EARTH_RADIUS_KM * 2 * Math.atan2(Math.sqrt(haversine), Math.sqrt(1 - haversine));
    }

    public static BigDecimal toBigDecimal(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }
}
