package com.fintech.ledger.api.dto;

import com.fintech.ledger.domain.Account;
import com.fintech.ledger.domain.AccountStatus;
import com.fintech.ledger.domain.AccountType;
import com.fintech.ledger.domain.Direction;

import java.time.Instant;
import java.util.UUID;

public record AccountResponse(
        UUID accountId,
        String accountCode,
        String accountName,
        AccountType accountType,
        Direction normalBalance,
        String currency,
        UUID ownerId,
        String ownerType,
        AccountStatus status,
        Instant createdAt
) {
    public static AccountResponse from(Account a) {
        return new AccountResponse(
                a.getAccountId(), a.getAccountCode(), a.getAccountName(),
                a.getAccountType(), a.getNormalBalance(), a.getCurrency(),
                a.getOwnerId(), a.getOwnerType(), a.getStatus(), a.getCreatedAt());
    }
}
