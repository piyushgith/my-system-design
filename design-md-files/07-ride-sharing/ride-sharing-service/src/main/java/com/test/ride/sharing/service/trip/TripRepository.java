package com.test.ride.sharing.service.trip;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TripRepository extends JpaRepository<Trip, UUID> {

    @Query("""
            SELECT t FROM Trip t
            WHERE t.riderId = :riderId
              AND t.status IN ('REQUESTED', 'MATCHING', 'DRIVER_MATCHED', 'DRIVER_ARRIVED', 'IN_PROGRESS')
            """)
    Optional<Trip> findActiveByRiderId(@Param("riderId") UUID riderId);

    @Query("""
            SELECT t FROM Trip t
            WHERE t.driverId = :driverId
              AND t.status IN ('DRIVER_MATCHED', 'DRIVER_ARRIVED', 'IN_PROGRESS')
            """)
    Optional<Trip> findActiveByDriverId(@Param("driverId") UUID driverId);

    Page<Trip> findByRiderIdOrderByRequestedAtDesc(UUID riderId, Pageable pageable);

    List<Trip> findByStatusAndCityId(TripStatus status, UUID cityId);
}
