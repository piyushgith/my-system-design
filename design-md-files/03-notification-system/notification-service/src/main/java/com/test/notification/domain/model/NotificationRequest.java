package com.test.notification.domain.model;

import com.test.notification.domain.enums.Category;
import com.test.notification.domain.enums.Channel;
import com.test.notification.domain.enums.NotificationStatus;
import com.test.notification.domain.enums.Priority;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(
    name = "notification_requests",
    indexes = {
        @Index(name = "idx_notification_user_status", columnList = "recipient_user_id, status, created_at"),
        @Index(name = "idx_notification_idempotency", columnList = "idempotency_key", unique = true)
    }
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class NotificationRequest {

    @Id
    @Column(name = "notification_id", nullable = false, updatable = false)
    private UUID notificationId;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 255)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Category category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Priority priority;

    @Column(name = "template_id", nullable = false, length = 100)
    private String templateId;

    @Column(name = "template_version")
    private Integer templateVersion;

    @Column(name = "recipient_user_id", nullable = false)
    private UUID recipientUserId;

    @Column(name = "batch_id")
    private UUID batchId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "TEXT", nullable = false)
    private Map<String, String> variables;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "notification_channels_override", joinColumns = @JoinColumn(name = "notification_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "channel")
    private List<Channel> channelsOverride;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private NotificationStatus status = NotificationStatus.PENDING;

    @Column(name = "scheduled_at")
    private Instant scheduledAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "dispatched_at")
    private Instant dispatchedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "producer_service", length = 100)
    private String producerService;

    @Column(name = "producer_trace_id", length = 255)
    private String producerTraceId;
}
