package com.test.notification.domain.repository;

import com.test.notification.domain.model.InAppInbox;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.UUID;

public interface InAppInboxRepository extends JpaRepository<InAppInbox, UUID> {

    Page<InAppInbox> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Page<InAppInbox> findByUserIdAndIsReadFalseOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    long countByUserIdAndIsReadFalse(UUID userId);

    @Modifying
    @Query("UPDATE InAppInbox i SET i.isRead = true, i.readAt = :now WHERE i.userId = :userId AND i.isRead = false")
    int markAllReadForUser(UUID userId, Instant now);

    @Modifying
    @Query("DELETE FROM InAppInbox i WHERE i.expiresAt < :now")
    int deleteExpired(Instant now);
}
