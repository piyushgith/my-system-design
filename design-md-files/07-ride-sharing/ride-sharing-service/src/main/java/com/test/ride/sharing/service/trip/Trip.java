package com.test.ride.sharing.service.trip;

import com.test.ride.sharing.service.shared.VehicleType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "trips")
@Getter
@Setter
@NoArgsConstructor
public class Trip {

    @Id
    private UUID tripId;

    @Column(nullable = false)
    private UUID riderId;

    private UUID driverId;

    private UUID vehicleId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private TripStatus status = TripStatus.REQUESTED;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private VehicleType vehicleTypeRequested;

    @Column(nullable = false, precision = 10, scale = 7)
    private BigDecimal pickupLat;

    @Column(nullable = false, precision = 10, scale = 7)
    private BigDecimal pickupLng;

    @Column(nullable = false)
    private String pickupAddress;

    @Column(nullable = false, precision = 10, scale = 7)
    private BigDecimal destinationLat;

    @Column(nullable = false, precision = 10, scale = 7)
    private BigDecimal destinationLng;

    @Column(nullable = false)
    private String destinationAddress;

    @Column(nullable = false, precision = 8, scale = 2)
    private BigDecimal estimatedDistanceKm;

    private BigDecimal actualDistanceKm;

    @Column(nullable = false)
    private int estimatedDurationMin;

    private Integer actualDurationMin;

    @Column(nullable = false)
    private int estimatedFareMin;

    @Column(nullable = false)
    private int estimatedFareMax;

    @Column(nullable = false, precision = 4, scale = 2)
    private BigDecimal surgeMultiplier;

    private Integer finalFare;

    private UUID paymentId;

    private String cancellationReason;

    private String cancelledBy;

    private Integer cancellationFee;

    @Column(nullable = false)
    private UUID cityId;

    @Column(nullable = false, length = 4)
    private String otp;

    @Column(nullable = false)
    private Instant requestedAt = Instant.now();

    private Instant matchedAt;
    private Instant driverArrivedAt;
    private Instant tripStartedAt;
    private Instant tripEndedAt;
    private Instant cancelledAt;

    @Version
    private long version;
}
