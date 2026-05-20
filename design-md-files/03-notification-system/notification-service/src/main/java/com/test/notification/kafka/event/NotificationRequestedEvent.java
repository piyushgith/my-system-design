package com.test.notification.kafka.event;

import com.test.notification.domain.enums.Category;
import com.test.notification.domain.enums.Channel;
import com.test.notification.domain.enums.Priority;
import lombok.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class NotificationRequestedEvent {

    private UUID notificationId;
    private String idempotencyKey;
    private Category category;
    private Priority priority;
    private String templateId;
    private Integer templateVersion;
    private UUID recipientUserId;
    private UUID batchId;
    private Map<String, String> variables;
    private List<Channel> channelsOverride;
    private Instant scheduledAt;
    private Instant expiresAt;
    private String producerService;
    private String producerTraceId;
    private Instant eventTimestamp;
}
