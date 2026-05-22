package com.fintech.ledger.exception;

import com.fintech.ledger.domain.AccountStatus;

import java.util.UUID;

public class AccountNotActiveException extends RuntimeException {
    public AccountNotActiveException(UUID accountId, AccountStatus status) {
        super("Account %s is %s — cannot receive postings".formatted(accountId, status));
    }
}
