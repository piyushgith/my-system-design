package com.test.ride.sharing.service.trip;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "trip_events")
@Getter
@Setter
@NoArgsConstructor
public class TripEvent {

    @Id
    private UUID eventId;

    @Column(nullable = false)
    private UUID tripId;

    @Column(nullable = false)
    private String eventType;

    private String previousStatus;
    private String newStatus;

    @Column(nullable = false)
    private UUID actorId;

    @Column(nullable = false)
    private String actorType;

    @Column(nullable = false)
    private Instant occurredAt = Instant.now();
}
