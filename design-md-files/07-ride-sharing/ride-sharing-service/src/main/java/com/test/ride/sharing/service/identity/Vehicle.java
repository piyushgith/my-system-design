package com.test.ride.sharing.service.identity;

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

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "vehicles")
@Getter
@Setter
@NoArgsConstructor
public class Vehicle {

    @Id
    private UUID vehicleId;

    @Column(nullable = false)
    private UUID driverId;

    @Column(nullable = false, unique = true)
    private String registrationNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private VehicleType vehicleType;

    @Column(nullable = false)
    private String make;

    @Column(nullable = false)
    private String model;

    @Column(name = "model_year", nullable = false)
    private int year;

    @Column(nullable = false)
    private String color;

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false)
    private Instant verifiedAt = Instant.now();
}
