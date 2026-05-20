package com.test.notification.domain.repository;

import com.test.notification.domain.enums.NotificationStatus;
import com.test.notification.domain.model.NotificationRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRequestRepository extends JpaRepository<NotificationRequest, UUID> {

    Optional<NotificationRequest> findByIdempotencyKey(String idempotencyKey);

    List<NotificationRequest> findByRecipientUserIdAndStatusOrderByCreatedAtDesc(
            UUID recipientUserId, NotificationStatus status);
}
