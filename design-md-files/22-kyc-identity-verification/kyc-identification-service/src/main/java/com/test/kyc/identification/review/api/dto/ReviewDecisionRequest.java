package com.test.kyc.identification.review.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

public record ReviewDecisionRequest(
        @NotNull UUID reviewerId,
        @NotBlank @Pattern(regexp = "APPROVED|REJECTED") String decision,
        String notes
) {}
