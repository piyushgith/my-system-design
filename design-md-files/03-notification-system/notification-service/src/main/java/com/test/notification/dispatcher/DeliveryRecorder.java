package com.test.notification.dispatcher;

import com.test.notification.domain.enums.Channel;
import com.test.notification.domain.enums.DeliveryStatus;
import com.test.notification.domain.enums.NotificationStatus;
import com.test.notification.domain.model.DeliveryAttempt;
import com.test.notification.domain.repository.DeliveryAttemptRepository;
import com.test.notification.domain.repository.NotificationRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class DeliveryRecorder {

    private final DeliveryAttemptRepository deliveryAttemptRepository;
    private final NotificationRequestRepository notificationRequestRepository;

    @Transactional
    public void record(UUID notificationId, Channel channel, String providerName,
                       EmailProvider.EmailSendResult result, int attemptNumber) {
        DeliveryAttempt attempt = DeliveryAttempt.builder()
                .notificationId(notificationId)
                .channel(channel)
                .provider(result.success() ? providerName : "unknown")
                .providerMessageId(result.providerMessageId())
                .status(result.success() ? DeliveryStatus.DELIVERED : DeliveryStatus.FAILED)
                .attemptNumber(attemptNumber)
                .attemptedAt(Instant.now())
                .deliveredAt(result.success() ? Instant.now() : null)
                .failureReason(result.failureReason())
                .failureCode(result.failureCode())
                .build();
        deliveryAttemptRepository.save(attempt);

        NotificationStatus status = result.success() ? NotificationStatus.DELIVERED : NotificationStatus.FAILED;
        notificationRequestRepository.updateStatus(notificationId, status, Instant.now());
    }

    @Transactional
    public void markExpired(UUID notificationId) {
        notificationRequestRepository.updateStatus(notificationId, NotificationStatus.EXPIRED, Instant.now());
    }
}
