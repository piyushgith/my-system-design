package com.test.kyc.identification.application.api.dto;

import com.test.kyc.identification.application.domain.KycApplication;

import java.time.Instant;
import java.util.UUID;

public record ApplicationStatusResponse(
        UUID applicationId,
        UUID userId,
        String status,
        String kycTier,
        Instant submittedAt,
        Instant approvedAt,
        Instant rejectedAt,
        String rejectionReason,
        String statusUrl
) {
    public static ApplicationStatusResponse from(KycApplication app) {
        return new ApplicationStatusResponse(
                app.getApplicationId(),
                app.getUserId(),
                app.getStatus().name(),
                app.getKycTier().name(),
                app.getCreatedAt(),
                app.getApprovedAt(),
                app.getRejectedAt(),
                app.getRejectionReason(),
                "/api/v1/kyc/applications/" + app.getApplicationId()
        );
    }
}
