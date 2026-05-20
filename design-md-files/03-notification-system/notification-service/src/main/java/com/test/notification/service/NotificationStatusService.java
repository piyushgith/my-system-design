package com.test.notification.service;

import com.test.notification.domain.model.DeliveryAttempt;
import com.test.notification.domain.model.NotificationRequest;
import com.test.notification.domain.repository.DeliveryAttemptRepository;
import com.test.notification.domain.repository.NotificationRequestRepository;
import com.test.notification.exception.NotificationNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class NotificationStatusService {

    private final NotificationRequestRepository notificationRepository;
    private final DeliveryAttemptRepository deliveryAttemptRepository;

    @Transactional(readOnly = true)
    public NotificationRequest getNotification(UUID notificationId) {
        return notificationRepository.findById(notificationId)
                .orElseThrow(() -> new NotificationNotFoundException(notificationId));
    }

    @Transactional(readOnly = true)
    public List<DeliveryAttempt> getDeliveryAttempts(UUID notificationId) {
        return deliveryAttemptRepository.findByNotificationIdOrderByAttemptedAtDesc(notificationId);
    }
}
