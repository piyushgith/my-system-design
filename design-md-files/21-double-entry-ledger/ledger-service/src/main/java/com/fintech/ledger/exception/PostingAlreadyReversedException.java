package com.fintech.ledger.exception;

import java.util.UUID;

public class PostingAlreadyReversedException extends RuntimeException {
    public PostingAlreadyReversedException(UUID postingId) {
        super("Posting %s is already reversed".formatted(postingId));
    }
}
