package com.test.ride.sharing.service.matching;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TripOfferRepository extends JpaRepository<TripOffer, UUID> {

    Optional<TripOffer> findByOfferIdAndDriverId(UUID offerId, UUID driverId);

    Optional<TripOffer> findFirstByTripIdAndStatusOrderByOfferedAtDesc(UUID tripId, OfferStatus status);

    Optional<TripOffer> findFirstByDriverIdAndStatusOrderByOfferedAtDesc(UUID driverId, OfferStatus status);

    @Query("""
            SELECT o.driverId FROM TripOffer o
            WHERE o.tripId = :tripId AND o.status IN ('REJECTED', 'EXPIRED')
            """)
    List<UUID> findDeclinedDriverIds(@Param("tripId") UUID tripId);

    List<TripOffer> findByTripIdOrderByOfferedAtDesc(UUID tripId);
}
