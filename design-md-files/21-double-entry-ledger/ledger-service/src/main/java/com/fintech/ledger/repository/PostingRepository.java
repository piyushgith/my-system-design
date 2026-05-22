package com.fintech.ledger.repository;

import com.fintech.ledger.domain.Posting;
import com.fintech.ledger.domain.PostingStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PostingRepository extends JpaRepository<Posting, UUID> {

    Optional<Posting> findByIdempotencyKey(String idempotencyKey);

    @Query("""
            SELECT DISTINCT p FROM Posting p
            JOIN p.legs e
            WHERE e.accountId = :accountId
              AND e.effectiveAt BETWEEN :from AND :to
            ORDER BY p.effectiveAt DESC
            """)
    Page<Posting> findByAccountAndDateRange(
            @Param("accountId") UUID accountId,
            @Param("from") Instant from,
            @Param("to") Instant to,
            Pageable pageable);

    boolean existsByIdempotencyKey(String idempotencyKey);
}
