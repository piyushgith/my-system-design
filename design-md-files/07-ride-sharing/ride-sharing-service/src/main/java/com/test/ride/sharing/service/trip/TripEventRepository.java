package com.test.ride.sharing.service.trip;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface TripEventRepository extends JpaRepository<TripEvent, UUID> {
}
