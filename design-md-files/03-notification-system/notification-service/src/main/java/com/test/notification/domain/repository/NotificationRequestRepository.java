package com.test.notification.domain.repository;

import com.test.notification.domain.enums.NotificationStatus;
import com.test.notification.domain.model.NotificationRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRequestRepository extends JpaRepository<NotificationRequest, UUID> {

    Optional<NotificationRequest> findByIdempotencyKey(String idempotencyKey);

    List<NotificationRequest> findByRecipientUserIdAndStatusOrderByCreatedAtDesc(
            UUID recipientUserId, NotificationStatus status);

    @Modifying
    @Query("UPDATE NotificationRequest n SET n.status = :status, n.completedAt = :completedAt WHERE n.notificationId = :id")
    void updateStatus(@Param("id") UUID id, @Param("status") NotificationStatus status,
                      @Param("completedAt") Instant completedAt);
}
