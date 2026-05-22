package com.fintech.ledger.exception;

import java.util.UUID;

public class AccountNotFoundException extends RuntimeException {
    public AccountNotFoundException(UUID accountId) {
        super("Account not found: " + accountId);
    }
    public AccountNotFoundException(String accountCode) {
        super("Account not found: " + accountCode);
    }
}
