package com.test.notification.domain.model;

import com.test.notification.domain.enums.Channel;
import com.test.notification.domain.enums.DeliveryStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
    name = "delivery_attempts",
    indexes = {
        @Index(name = "idx_delivery_notification", columnList = "notification_id, channel, attempted_at"),
        @Index(name = "idx_delivery_provider_ref", columnList = "provider_message_id")
    }
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class DeliveryAttempt {

    @Id
    @Column(name = "attempt_id", nullable = false, updatable = false)
    @Builder.Default
    private UUID attemptId = UUID.randomUUID();

    @Column(name = "notification_id", nullable = false)
    private UUID notificationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Channel channel;

    @Column(nullable = false, length = 50)
    private String provider;

    @Column(name = "provider_message_id", length = 255)
    private String providerMessageId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private DeliveryStatus status = DeliveryStatus.PENDING;

    @Column(name = "attempt_number", nullable = false)
    @Builder.Default
    private Integer attemptNumber = 1;

    @Column(name = "attempted_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant attemptedAt = Instant.now();

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "failure_code", length = 50)
    private String failureCode;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;
}
