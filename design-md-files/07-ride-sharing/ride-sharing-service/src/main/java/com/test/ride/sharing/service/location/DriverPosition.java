package com.test.ride.sharing.service.location;

import com.test.ride.sharing.service.shared.GeoPoint;
import com.test.ride.sharing.service.shared.VehicleType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record DriverPosition(
        UUID driverId,
        UUID cityId,
        UUID vehicleId,
        VehicleType vehicleType,
        GeoPoint position,
        Integer heading,
        BigDecimal speedKmh,
        Instant updatedAt,
        DriverAvailabilityStatus status,
        UUID currentTripId
) {
}
