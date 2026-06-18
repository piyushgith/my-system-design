package com.test.ride.sharing.service.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class MockKafkaDomainEventPublisher implements DomainEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(MockKafkaDomainEventPublisher.class);

    @Override
    public String name() {
        return "mock-kafka";
    }

    @Override
    public void publish(TripDomainEvent event) {
        log.info("[mock-kafka] topic={} type={} tripId={} riderId={} driverId={} payload={}",
                event.topic(), event.eventType(), event.tripId(), event.riderId(), event.driverId(), event.payloadJson());
    }
}
