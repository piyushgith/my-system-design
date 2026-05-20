package com.pastebin.paste.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ExpiryScheduleJpaRepository extends JpaRepository<ExpiryScheduleEntity, UUID> {

    @Query("""
            SELECT e FROM ExpiryScheduleEntity e
            WHERE e.processed = false AND e.expiresAt <= :now
            ORDER BY e.expiresAt ASC
            """)
    List<ExpiryScheduleEntity> findPendingExpirations(Instant now);
}
