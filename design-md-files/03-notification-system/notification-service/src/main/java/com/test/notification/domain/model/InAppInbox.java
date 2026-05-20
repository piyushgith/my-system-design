package com.test.notification.domain.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
    name = "in_app_inbox",
    indexes = {
        @Index(name = "idx_inbox_user_unread", columnList = "user_id, created_at"),
        @Index(name = "idx_inbox_user_all", columnList = "user_id, created_at")
    }
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class InAppInbox {

    @Id
    @Column(name = "inbox_item_id", nullable = false, updatable = false)
    @Builder.Default
    private UUID inboxItemId = UUID.randomUUID();

    @Column(name = "notification_id", nullable = false)
    private UUID notificationId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(name = "action_url", length = 2048)
    private String actionUrl;

    @Column(name = "image_url", length = 2048)
    private String imageUrl;

    @Column(name = "is_read", nullable = false)
    @Builder.Default
    private boolean isRead = false;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    @Builder.Default
    private Instant expiresAt = Instant.now().plusSeconds(30L * 24 * 3600);
}
