package com.test.ride.sharing.service.routing;

import com.test.ride.sharing.service.shared.GeoPoint;

public record RouteEstimate(double distanceKm, int durationMinutes) {
}
