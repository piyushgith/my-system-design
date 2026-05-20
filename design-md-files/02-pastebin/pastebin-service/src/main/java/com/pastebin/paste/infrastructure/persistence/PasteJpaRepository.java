package com.pastebin.paste.infrastructure.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PasteJpaRepository extends JpaRepository<PasteEntity, UUID> {

    @Query("SELECT p FROM PasteEntity p WHERE p.shortKey = :shortKey")
    Optional<PasteEntity> findByShortKeyIncludingDeleted(@Param("shortKey") String shortKey);

    @Query("""
            SELECT p FROM PasteEntity p
            WHERE p.ownerId = :ownerId AND p.deleted = false
            AND (:includeExpired = true OR p.expiresAt IS NULL OR p.expiresAt > :now)
            AND (:cursorCreatedAt IS NULL OR p.createdAt < :cursorCreatedAt
                 OR (p.createdAt = :cursorCreatedAt AND p.id < :cursorId))
            ORDER BY p.createdAt DESC, p.id DESC
            """)
    List<PasteEntity> findUserPastes(@Param("ownerId") UUID ownerId,
                                     @Param("now") Instant now,
                                     @Param("includeExpired") boolean includeExpired,
                                     @Param("cursorCreatedAt") Instant cursorCreatedAt,
                                     @Param("cursorId") UUID cursorId,
                                     Pageable pageable);
}
