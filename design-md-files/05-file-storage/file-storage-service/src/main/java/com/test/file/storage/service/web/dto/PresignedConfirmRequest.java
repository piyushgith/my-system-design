package com.test.file.storage.service.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record PresignedConfirmRequest(
        @NotBlank String contentHash,
        @Positive long sizeBytes) {
}
