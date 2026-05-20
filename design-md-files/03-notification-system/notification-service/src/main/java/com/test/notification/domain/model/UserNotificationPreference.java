package com.test.notification.domain.model;

import com.test.notification.domain.enums.Category;
import com.test.notification.domain.enums.Channel;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

@Entity
@Table(name = "user_notification_preferences")
@IdClass(UserPreferenceId.class)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class UserNotificationPreference {

    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Channel channel;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Category category;

    @Column(name = "opted_in", nullable = false)
    @Builder.Default
    private boolean optedIn = true;

    @Column(name = "quiet_hours_start")
    private LocalTime quietHoursStart;

    @Column(name = "quiet_hours_end")
    private LocalTime quietHoursEnd;

    @Column(name = "timezone", length = 50)
    @Builder.Default
    private String timezone = "UTC";

    @Column(name = "unsubscribe_token", unique = true, length = 64)
    private String unsubscribeToken;

    @Column(name = "hard_unsubscribed", nullable = false)
    @Builder.Default
    private boolean hardUnsubscribed = false;

    @Column(name = "hard_unsubscribed_at")
    private Instant hardUnsubscribedAt;

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
