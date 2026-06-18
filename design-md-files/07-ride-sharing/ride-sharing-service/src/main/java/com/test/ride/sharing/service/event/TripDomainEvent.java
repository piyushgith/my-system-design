package com.test.ride.sharing.service.event;

import java.time.Instant;
import java.util.UUID;

public record TripDomainEvent(
        String topic,
        String eventType,
        UUID tripId,
        UUID riderId,
        UUID driverId,
        String payloadJson,
        Instant occurredAt
) {
}
