package com.test.ride.sharing.service.routing;

import com.test.ride.sharing.service.shared.GeoPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Tries OSRM first; falls back to haversine mock when OSRM container is not running.
 */
@Component
@Primary
public class OsrmFallbackRoutingStrategy implements RoutingStrategy {

    private static final Logger log = LoggerFactory.getLogger(OsrmFallbackRoutingStrategy.class);

    private final OsrmRoutingStrategy osrmRoutingStrategy;
    private final MockRoutingStrategy mockRoutingStrategy;

    public OsrmFallbackRoutingStrategy(OsrmRoutingStrategy osrmRoutingStrategy,
                                       MockRoutingStrategy mockRoutingStrategy) {
        this.osrmRoutingStrategy = osrmRoutingStrategy;
        this.mockRoutingStrategy = mockRoutingStrategy;
    }

    @Override
    public String name() {
        return "osrm-fallback";
    }

    @Override
    public RouteEstimate estimate(GeoPoint origin, GeoPoint destination) {
        try {
            return osrmRoutingStrategy.estimate(origin, destination);
        } catch (Exception ex) {
            log.warn("OSRM unavailable ({}), falling back to mock routing", ex.getMessage());
            return mockRoutingStrategy.estimate(origin, destination);
        }
    }
}
