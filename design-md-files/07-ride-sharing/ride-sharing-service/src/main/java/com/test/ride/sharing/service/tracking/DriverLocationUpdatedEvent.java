package com.test.ride.sharing.service.tracking;

import java.util.UUID;

public record DriverLocationUpdatedEvent(UUID driverId, UUID tripId) {
}
