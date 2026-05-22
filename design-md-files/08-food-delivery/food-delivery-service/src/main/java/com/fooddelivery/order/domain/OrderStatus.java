package com.fooddelivery.order.domain;

import java.util.Set;

public enum OrderStatus {
    PAYMENT_PENDING,
    PAYMENT_CONFIRMED,
    PAYMENT_FAILED,
    RESTAURANT_NOTIFIED,
    RESTAURANT_ACCEPTED,
    RESTAURANT_REJECTED,
    FOOD_BEING_PREPARED,
    FOOD_READY,
    DELIVERY_PARTNER_ASSIGNED,
    PARTNER_AT_RESTAURANT,
    PICKED_UP,
    OUT_FOR_DELIVERY,
    DELIVERED,
    CANCELLING,
    CANCELLED;

    private static final Set<OrderStatus> TERMINAL = Set.of(DELIVERED, CANCELLED, PAYMENT_FAILED);
    private static final Set<OrderStatus> CANCELLABLE_BY_CUSTOMER = Set.of(
            PAYMENT_PENDING, PAYMENT_CONFIRMED, RESTAURANT_NOTIFIED, RESTAURANT_ACCEPTED
    );

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    public boolean isCancellableByCustomer() {
        return CANCELLABLE_BY_CUSTOMER.contains(this);
    }
}
