package com.test.kyc.identification.application.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record PresignedUrlRequest(
        @NotBlank String documentType,
        @NotBlank String side,
        @Positive long fileSizeBytes,
        @NotBlank String contentType
) {}
