package com.test.ride.sharing.service.location;

import com.test.ride.sharing.service.shared.GeoPoint;
import com.test.ride.sharing.service.shared.VehicleType;

import java.math.BigDecimal;
import java.util.UUID;

public record NearbyDriver(
        UUID driverId,
        GeoPoint position,
        double distanceKm,
        int etaSeconds,
        BigDecimal rating,
        VehicleType vehicleType
) {
}
