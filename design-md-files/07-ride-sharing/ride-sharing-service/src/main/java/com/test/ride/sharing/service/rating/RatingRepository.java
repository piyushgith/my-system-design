package com.test.ride.sharing.service.rating;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface RatingRepository extends JpaRepository<Rating, UUID> {
    Optional<Rating> findByTripIdAndRatedBy(UUID tripId, String ratedBy);
}
