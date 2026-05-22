package com.fintech.ledger.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record ReversePostingRequest(
        @NotBlank @Size(max = 255) String idempotencyKey,
        String reason,
        @NotNull Instant effectiveAt
) {}
