package com.fintech.ledger.exception;

import java.util.UUID;

public class PostingNotFoundException extends RuntimeException {
    public PostingNotFoundException(UUID postingId) {
        super("Posting not found: " + postingId);
    }
}
