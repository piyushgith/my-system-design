package com.test.ride.sharing.service.matching;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "trip_offers")
@Getter
@Setter
@NoArgsConstructor
public class TripOffer {

    @Id
    private UUID offerId;

    @Column(nullable = false)
    private UUID tripId;

    @Column(nullable = false)
    private UUID driverId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OfferStatus status = OfferStatus.PENDING;

    @Column(nullable = false)
    private int matchingRound;

    @Column(nullable = false)
    private Instant offeredAt = Instant.now();

    @Column(nullable = false)
    private Instant expiresAt;
}
