package com.test.ride.sharing.service.location;

import com.test.ride.sharing.service.shared.GeoPoint;
import com.test.ride.sharing.service.shared.VehicleType;

import java.util.List;
import java.util.UUID;

public interface LocationStoreStrategy {

    String name();

    void upsert(DriverPosition position);

    void remove(UUID driverId);

    List<NearbyDriver> findNearby(UUID cityId, GeoPoint center, double radiusKm, VehicleType vehicleType, int limit);

    DriverPosition get(UUID driverId);
}
