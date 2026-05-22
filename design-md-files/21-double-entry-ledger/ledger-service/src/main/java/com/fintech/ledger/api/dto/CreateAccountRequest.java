package com.fintech.ledger.api.dto;

import com.fintech.ledger.domain.AccountType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateAccountRequest(
        @NotBlank @Size(max = 64) String accountCode,
        @NotBlank @Size(max = 255) String accountName,
        @NotNull AccountType accountType,
        @NotBlank @Size(min = 3, max = 3) String currency,
        UUID ownerId,
        String ownerType
) {}
