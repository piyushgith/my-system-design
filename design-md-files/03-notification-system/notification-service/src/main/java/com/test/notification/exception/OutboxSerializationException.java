package com.test.notification.exception;

import java.util.UUID;

public class OutboxSerializationException extends RuntimeException {
    public OutboxSerializationException(UUID notificationId, Throwable cause) {
        super("Failed to serialize outbox event for notification " + notificationId, cause);
    }
}
