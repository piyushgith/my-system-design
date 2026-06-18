package com.test.ride.sharing.service.routing;

import com.test.ride.sharing.service.shared.GeoPoint;
import com.test.ride.sharing.service.shared.GeoUtils;
import org.springframework.stereotype.Component;

@Component
public class MockRoutingStrategy implements RoutingStrategy {

    private final RoutingProperties properties;

    public MockRoutingStrategy(RoutingProperties properties) {
        this.properties = properties;
    }

    @Override
    public String name() {
        return "mock";
    }

    @Override
    public RouteEstimate estimate(GeoPoint origin, GeoPoint destination) {
        double distanceKm = GeoUtils.distanceKm(origin, destination);
        int durationMinutes = (int) Math.max(1, Math.ceil((distanceKm / properties.getMock().getAvgSpeedKmh()) * 60));
        return new RouteEstimate(distanceKm, durationMinutes);
    }
}
