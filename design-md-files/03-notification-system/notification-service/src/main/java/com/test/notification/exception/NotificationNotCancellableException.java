package com.test.notification.exception;

import java.util.UUID;

public class NotificationNotCancellableException extends RuntimeException {
    public NotificationNotCancellableException(UUID notificationId) {
        super("Notification cannot be cancelled (already dispatched or delivered): " + notificationId);
    }
}
