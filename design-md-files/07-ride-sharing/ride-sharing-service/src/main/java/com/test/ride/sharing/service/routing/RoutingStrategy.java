package com.test.ride.sharing.service.routing;

import com.test.ride.sharing.service.shared.GeoPoint;

public interface RoutingStrategy {

    String name();

    RouteEstimate estimate(GeoPoint origin, GeoPoint destination);
}
