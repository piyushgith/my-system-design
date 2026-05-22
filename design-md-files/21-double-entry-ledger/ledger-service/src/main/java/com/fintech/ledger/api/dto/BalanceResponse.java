package com.fintech.ledger.api.dto;

import com.fintech.ledger.domain.Direction;

import java.time.Instant;
import java.util.UUID;

public record BalanceResponse(
        UUID accountId,
        long balance,
        String currency,
        Direction normalBalanceDirection,
        String freshness,
        Instant asOf
) {}
