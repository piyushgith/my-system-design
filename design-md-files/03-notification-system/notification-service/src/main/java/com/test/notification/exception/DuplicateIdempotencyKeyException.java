package com.test.notification.exception;

import java.util.UUID;

public class DuplicateIdempotencyKeyException extends RuntimeException {
    private final UUID existingNotificationId;

    public DuplicateIdempotencyKeyException(String idempotencyKey, UUID existingNotificationId) {
        super("Duplicate idempotency key: " + idempotencyKey);
        this.existingNotificationId = existingNotificationId;
    }

    public UUID getExistingNotificationId() {
        return existingNotificationId;
    }
}
