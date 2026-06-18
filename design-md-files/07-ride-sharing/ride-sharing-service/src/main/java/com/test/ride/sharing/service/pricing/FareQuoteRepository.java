package com.test.ride.sharing.service.pricing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface FareQuoteRepository extends JpaRepository<FareQuote, UUID> {
}
