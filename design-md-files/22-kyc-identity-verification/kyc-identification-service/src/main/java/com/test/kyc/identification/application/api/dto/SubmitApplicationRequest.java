package com.test.kyc.identification.application.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record SubmitApplicationRequest(
        String idempotencyKey,

        @NotNull UUID userId,
        @NotBlank String kycTier,

        @NotNull Map<String, Object> personalData,

        @NotEmpty @Valid List<DocumentRef> documents
) {
    public record DocumentRef(
            @NotBlank String documentType,
            @NotBlank String side,
            @NotBlank String documentKey
    ) {}
}
