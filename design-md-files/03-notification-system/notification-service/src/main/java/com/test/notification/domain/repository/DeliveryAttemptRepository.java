package com.test.notification.domain.repository;

import com.test.notification.domain.model.DeliveryAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DeliveryAttemptRepository extends JpaRepository<DeliveryAttempt, UUID> {

    List<DeliveryAttempt> findByNotificationIdOrderByAttemptedAtDesc(UUID notificationId);

    int countByNotificationId(UUID notificationId);
}
