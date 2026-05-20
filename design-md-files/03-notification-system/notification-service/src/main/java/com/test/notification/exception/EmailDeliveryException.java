package com.test.notification.exception;

public class EmailDeliveryException extends RuntimeException {
    public EmailDeliveryException(String reason) {
        super("Email delivery failed: " + reason);
    }
}
