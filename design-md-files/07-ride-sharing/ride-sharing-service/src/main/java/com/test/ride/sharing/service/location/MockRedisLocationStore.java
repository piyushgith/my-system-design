package com.test.ride.sharing.service.location;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Wraps the in-memory store but logs Redis GEO commands — stand-in for ElastiCache in V1.
 */
@Component
public class MockRedisLocationStore implements LocationStoreStrategy {

    private static final Logger log = LoggerFactory.getLogger(MockRedisLocationStore.class);

    private final InMemoryLocationStore delegate;

    public MockRedisLocationStore(InMemoryLocationStore delegate) {
        this.delegate = delegate;
    }

    @Override
    public String name() {
        return "mock-redis";
    }

    @Override
    public void upsert(DriverPosition position) {
        log.debug("[mock-redis] GEOADD driver_locations:{} {} {} {}",
                position.cityId(), position.position().getLng(), position.position().getLat(), position.driverId());
        delegate.upsert(position);
    }

    @Override
    public void remove(UUID driverId) {
        log.debug("[mock-redis] ZREM driver {}", driverId);
        delegate.remove(driverId);
    }

    @Override
    public List<NearbyDriver> findNearby(UUID cityId, com.test.ride.sharing.service.shared.GeoPoint center,
                                         double radiusKm, com.test.ride.sharing.service.shared.VehicleType vehicleType,
                                         int limit) {
        log.debug("[mock-redis] GEORADIUS driver_locations:{} radius={}km", cityId, radiusKm);
        return delegate.findNearby(cityId, center, radiusKm, vehicleType, limit);
    }

    @Override
    public DriverPosition get(UUID driverId) {
        return delegate.get(driverId);
    }
}
