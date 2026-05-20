package com.test.notification.kafka.event;

import com.test.notification.domain.enums.Channel;
import com.test.notification.domain.enums.DeliveryStatus;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class DeliveryResultEvent {

    private UUID notificationId;
    private UUID attemptId;
    private Channel channel;
    private String provider;
    private String providerMessageId;
    private DeliveryStatus status;
    private Integer attemptNumber;
    private String failureReason;
    private String failureCode;
    private Instant attemptedAt;
    private Instant deliveredAt;
}
