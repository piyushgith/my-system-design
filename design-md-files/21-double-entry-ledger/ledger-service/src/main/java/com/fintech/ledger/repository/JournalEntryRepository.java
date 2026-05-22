package com.fintech.ledger.repository;

import com.fintech.ledger.domain.Direction;
import com.fintech.ledger.domain.JournalEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, UUID> {

    List<JournalEntry> findByAccountIdOrderByEffectiveAtDesc(UUID accountId);

    // Sum for a direction on an account — used in balance computation
    @Query("""
            SELECT COALESCE(SUM(e.amount), 0)
            FROM JournalEntry e
            WHERE e.accountId = :accountId
              AND e.direction = :direction
            """)
    Long sumByAccountAndDirection(@Param("accountId") UUID accountId,
                                   @Param("direction") Direction direction);

    // Point-in-time balance components
    @Query("""
            SELECT COALESCE(SUM(e.amount), 0)
            FROM JournalEntry e
            WHERE e.accountId = :accountId
              AND e.direction = :direction
              AND e.effectiveAt <= :asOf
            """)
    Long sumByAccountAndDirectionAsOf(@Param("accountId") UUID accountId,
                                       @Param("direction") Direction direction,
                                       @Param("asOf") Instant asOf);
}
