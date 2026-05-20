package com.test.notification.api.dto;

import com.test.notification.domain.enums.NotificationStatus;
import lombok.Builder;
import lombok.Getter;

import java.util.UUID;

@Getter @Builder
public class SubmitNotificationResponse {
    private UUID notificationId;
    private NotificationStatus status;
    private String idempotencyKey;
    private int estimatedDeliverySeconds;
}
