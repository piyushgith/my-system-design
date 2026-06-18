package com.test.ride.sharing.service.identity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VehicleRepository extends JpaRepository<Vehicle, UUID> {
    List<Vehicle> findByDriverIdAndActiveTrue(UUID driverId);

    Optional<Vehicle> findByVehicleIdAndDriverId(UUID vehicleId, UUID driverId);
}
