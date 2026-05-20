package com.test.kyc.identification.application.api.dto;

public record PresignedUrlResponse(
        String uploadUrl,
        String documentKey,
        int expiresInSeconds,
        long maxFileSizeBytes
) {}
