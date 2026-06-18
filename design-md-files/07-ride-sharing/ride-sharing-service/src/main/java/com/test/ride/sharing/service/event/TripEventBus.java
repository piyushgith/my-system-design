package com.test.ride.sharing.service.event;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class TripEventBus {

    private final DomainEventPublisherResolver publisherResolver;

    public TripEventBus(DomainEventPublisherResolver publisherResolver) {
        this.publisherResolver = publisherResolver;
    }

    public void publish(String eventType, UUID tripId, UUID riderId, UUID driverId, String payloadJson) {
        publisherResolver.active().publish(new TripDomainEvent(
                "trip-events",
                eventType,
                tripId,
                riderId,
                driverId,
                payloadJson,
                Instant.now()
        ));
    }
}
