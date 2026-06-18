package com.test.ride.sharing.service.identity;

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
@Table(name = "riders")
@Getter
@Setter
@NoArgsConstructor
public class Rider {

    public enum Status {
        ACTIVE, SUSPENDED, DELETED
    }

    @Id
    private UUID riderId;

    @Column(nullable = false, unique = true, length = 20)
    private String phoneNumber;

    @Column
    private String email;

    @Column(nullable = false)
    private String fullName;

    @Column(nullable = false, precision = 3, scale = 2)
    private BigDecimal rating = BigDecimal.valueOf(5.0);

    @Column(nullable = false)
    private int totalTrips = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.ACTIVE;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column
    private Instant lastTripAt;
}
