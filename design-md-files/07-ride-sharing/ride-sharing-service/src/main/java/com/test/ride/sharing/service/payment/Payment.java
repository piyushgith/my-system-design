package com.test.ride.sharing.service.payment;

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
@Table(name = "payments")
@Getter
@Setter
@NoArgsConstructor
public class Payment {

    @Id
    private UUID paymentId;

    @Column(nullable = false, unique = true)
    private UUID tripId;

    @Column(nullable = false)
    private UUID riderId;

    @Column(nullable = false)
    private UUID driverId;

    @Column(nullable = false)
    private int amount;

    @Column(nullable = false, length = 3)
    private String currency = "INR";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PaymentStatus status = PaymentStatus.PENDING;

    @Column(nullable = false)
    private int driverShare;

    @Column(nullable = false)
    private int platformCommission;

    @Column(nullable = false, unique = true)
    private String idempotencyKey;

    private String gatewayTransactionId;

    @Column(nullable = false)
    private Instant initiatedAt = Instant.now();

    private Instant capturedAt;
}
