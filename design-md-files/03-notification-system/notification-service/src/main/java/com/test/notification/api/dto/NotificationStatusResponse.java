package com.test.notification.api.dto;

import com.test.notification.domain.enums.Category;
import com.test.notification.domain.enums.NotificationStatus;
import com.test.notification.domain.enums.Priority;
import com.test.notification.domain.model.DeliveryAttempt;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Getter @Builder
public class NotificationStatusResponse {
    private UUID notificationId;
    private NotificationStatus status;
    private Category category;
    private Priority priority;
    private UUID recipientUserId;
    private Instant createdAt;
    private Instant completedAt;
    private List<DeliveryAttemptDto> deliveryAttempts;

    @Getter @Builder
    public static class DeliveryAttemptDto {
        private String channel;
        private String status;
        private String provider;
        private String providerMessageId;
        private int attemptNumber;
        private Instant attemptedAt;
        private Instant deliveredAt;
        private String failureReason;

        public static DeliveryAttemptDto from(DeliveryAttempt a) {
            return DeliveryAttemptDto.builder()
                    .channel(a.getChannel().name())
                    .status(a.getStatus().name())
                    .provider(a.getProvider())
                    .providerMessageId(a.getProviderMessageId())
                    .attemptNumber(a.getAttemptNumber())
                    .attemptedAt(a.getAttemptedAt())
                    .deliveredAt(a.getDeliveredAt())
                    .failureReason(a.getFailureReason())
                    .build();
        }
    }
}
