package com.test.file.storage.service.web.dto;

import java.time.Instant;

public record PresignedUploadInitResponse(
        String sessionId,
        String presignedUrl,
        Instant expiresAt) {
}
