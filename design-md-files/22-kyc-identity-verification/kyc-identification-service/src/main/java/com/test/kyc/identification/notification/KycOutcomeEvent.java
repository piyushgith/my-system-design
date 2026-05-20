package com.test.kyc.identification.notification;

import java.time.Instant;
import java.util.UUID;

public record KycOutcomeEvent(
        UUID applicationId,
        String outcome,
        String rejectionReason,
        Instant occurredAt
) {}
