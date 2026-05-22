package com.fintech.ledger.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record CreatePostingRequest(
        @NotBlank @Size(max = 255) String idempotencyKey,
        @NotBlank @Size(max = 50) String referenceType,
        UUID referenceId,
        @NotNull Instant effectiveAt,
        String description,
        @NotEmpty @Size(min = 2) @Valid List<PostingLegRequest> legs,
        Map<String, String> metadata
) {}
