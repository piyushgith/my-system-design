package com.test.ride.sharing.service.location;

import com.test.ride.sharing.service.shared.GeoPoint;
import com.test.ride.sharing.service.shared.GeoUtils;
import com.test.ride.sharing.service.shared.VehicleType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Component
public class InMemoryLocationStore implements LocationStoreStrategy {

    private final Map<UUID, DriverPosition> positions = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "memory";
    }

    @Override
    public void upsert(DriverPosition position) {
        positions.put(position.driverId(), position);
    }

    @Override
    public void remove(UUID driverId) {
        positions.remove(driverId);
    }

    @Override
    public List<NearbyDriver> findNearby(UUID cityId, GeoPoint center, double radiusKm,
                                         VehicleType vehicleType, int limit) {
        return positions.values().stream()
                .filter(p -> p.cityId().equals(cityId))
                .filter(p -> p.status() == DriverAvailabilityStatus.AVAILABLE)
                .filter(p -> vehicleType == null || p.vehicleType() == vehicleType)
                .map(p -> toNearby(center, p))
                .filter(n -> n.distanceKm() <= radiusKm)
                .sorted(Comparator.comparingDouble(NearbyDriver::distanceKm))
                .limit(limit)
                .collect(Collectors.toList());
    }

    @Override
    public DriverPosition get(UUID driverId) {
        return positions.get(driverId);
    }

    private NearbyDriver toNearby(GeoPoint center, DriverPosition position) {
        double distanceKm = GeoUtils.distanceKm(center, position.position());
        int etaSeconds = (int) Math.max(60, distanceKm / 30.0 * 3600);
        return new NearbyDriver(
                position.driverId(),
                position.position(),
                distanceKm,
                etaSeconds,
                BigDecimal.valueOf(4.8),
                position.vehicleType()
        );
    }
}
