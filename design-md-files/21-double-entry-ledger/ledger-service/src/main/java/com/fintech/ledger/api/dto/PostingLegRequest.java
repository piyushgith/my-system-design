package com.fintech.ledger.api.dto;

import com.fintech.ledger.domain.Direction;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record PostingLegRequest(
        @NotNull UUID accountId,
        @NotNull Direction direction,
        @NotNull @Min(1) Long amount,
        @NotBlank @Size(min = 3, max = 3) String currency,
        String description
) {}
