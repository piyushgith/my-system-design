package com.test.ride.sharing.service.event;

public interface DomainEventPublisher {

    String name();

    void publish(TripDomainEvent event);
}
