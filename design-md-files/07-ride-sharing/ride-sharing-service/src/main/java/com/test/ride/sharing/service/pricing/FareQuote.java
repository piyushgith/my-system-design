package com.test.ride.sharing.service.pricing;

import com.test.ride.sharing.service.shared.VehicleType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "fare_quotes")
@Getter
@Setter
@NoArgsConstructor
public class FareQuote {

    @Id
    private UUID quoteId;

    @Column(nullable = false)
    private UUID riderId;

    @Column(nullable = false, precision = 10, scale = 7)
    private BigDecimal pickupLat;

    @Column(nullable = false, precision = 10, scale = 7)
    private BigDecimal pickupLng;

    @Column(nullable = false, precision = 10, scale = 7)
    private BigDecimal destinationLat;

    @Column(nullable = false, precision = 10, scale = 7)
    private BigDecimal destinationLng;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private VehicleType vehicleType;

    @Column(nullable = false)
    private int baseFare;

    @Column(nullable = false)
    private int distanceFare;

    @Column(nullable = false)
    private int timeFare;

    @Column(nullable = false, precision = 4, scale = 2)
    private BigDecimal surgeMultiplier;

    @Column(nullable = false)
    private int platformFee;

    @Column(nullable = false)
    private int totalFareMin;

    @Column(nullable = false)
    private int totalFareMax;

    @Column(nullable = false)
    private UUID cityId;

    @Column(nullable = false, precision = 8, scale = 2)
    private BigDecimal estimatedDistanceKm;

    @Column(nullable = false)
    private int estimatedDurationMin;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private boolean used = false;
}
