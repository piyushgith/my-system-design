package com.test.notification.api.dto;

import com.test.notification.domain.enums.Category;
import com.test.notification.domain.enums.Channel;
import com.test.notification.domain.enums.Priority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Getter @Setter
public class SubmitNotificationRequest {

    @NotNull
    private Category category;

    @NotNull
    private Priority priority;

    @NotNull
    private UUID recipientUserId;

    @NotBlank
    private String templateId;

    private Integer templateVersion;

    private Map<String, String> variables;

    private List<Channel> channelsOverride;

    private Instant scheduledAt;

    private Instant expiresAt;

    private ProducerContext producerContext;

    @Getter @Setter
    public static class ProducerContext {
        private String service;
        private String traceId;
    }
}
