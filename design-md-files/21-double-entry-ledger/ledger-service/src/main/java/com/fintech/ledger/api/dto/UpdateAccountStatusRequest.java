package com.fintech.ledger.api.dto;

import com.fintech.ledger.domain.AccountStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateAccountStatusRequest(
        @NotNull AccountStatus status,
        String reason
) {}
