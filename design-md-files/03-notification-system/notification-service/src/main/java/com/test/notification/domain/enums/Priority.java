package com.test.notification.domain.enums;

public enum Priority {
    CRITICAL(5),
    HIGH(10),
    NORMAL(30),
    LOW(60);

    private final int estimatedDeliverySeconds;

    Priority(int estimatedDeliverySeconds) {
        this.estimatedDeliverySeconds = estimatedDeliverySeconds;
    }

    public int getEstimatedDeliverySeconds() {
        return estimatedDeliverySeconds;
    }
}
