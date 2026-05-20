package com.test.kyc.identification.application.api.dto;

import com.test.kyc.identification.application.domain.KycApplication;

import java.time.Instant;
import java.util.UUID;

public record SubmitApplicationResponse(
        UUID applicationId,
        String status,
        String kycTier,
        int estimatedCompletionSeconds,
        Instant submittedAt,
        String statusUrl
) {
    public static SubmitApplicationResponse from(KycApplication app) {
        return new SubmitApplicationResponse(
                app.getApplicationId(),
                app.getStatus().name(),
                app.getKycTier().name(),
                180,
                app.getCreatedAt(),
                "/api/v1/kyc/applications/" + app.getApplicationId()
        );
    }
}
