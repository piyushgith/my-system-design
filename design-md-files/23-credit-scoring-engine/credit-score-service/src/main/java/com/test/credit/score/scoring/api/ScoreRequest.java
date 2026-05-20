package com.test.credit.score.scoring.api;

import com.test.credit.score.scoring.domain.ProductType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ScoreRequest(
        @NotBlank String requestId,
        @NotBlank String userId,
        @NotNull  ProductType productType,
        boolean forceRefresh,
        String consentReferenceId
) {}
