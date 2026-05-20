package com.test.kyc.identification.notification;

import java.time.Instant;
import java.util.UUID;

public record KycManualReviewEvent(
        UUID applicationId,
        String routingReason,
        String priority,
        Instant occurredAt
) {}
