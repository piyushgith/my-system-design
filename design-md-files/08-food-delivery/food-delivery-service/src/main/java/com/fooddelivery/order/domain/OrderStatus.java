package com.fooddelivery.order.domain;

import java.util.*;

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

    private static final Set<OrderStatus> TERMINAL = Set.of(
            DELIVERED, CANCELLED, PAYMENT_FAILED, RESTAURANT_REJECTED
    );

    private static final Set<OrderStatus> CANCELLABLE_BY_CUSTOMER = Set.of(
            PAYMENT_PENDING, PAYMENT_CONFIRMED, RESTAURANT_NOTIFIED, RESTAURANT_ACCEPTED
    );

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED_TRANSITIONS;

    static {
        Map<OrderStatus, Set<OrderStatus>> map = new EnumMap<>(OrderStatus.class);
        map.put(PAYMENT_PENDING,           EnumSet.of(PAYMENT_CONFIRMED, PAYMENT_FAILED, CANCELLED));
        map.put(PAYMENT_CONFIRMED,         EnumSet.of(RESTAURANT_NOTIFIED, CANCELLED));
        map.put(RESTAURANT_NOTIFIED,       EnumSet.of(RESTAURANT_ACCEPTED, RESTAURANT_REJECTED, CANCELLED));
        map.put(RESTAURANT_ACCEPTED,       EnumSet.of(FOOD_BEING_PREPARED, FOOD_READY, CANCELLED));
        map.put(FOOD_BEING_PREPARED,       EnumSet.of(FOOD_READY));
        map.put(FOOD_READY,                EnumSet.of(DELIVERY_PARTNER_ASSIGNED));
        map.put(DELIVERY_PARTNER_ASSIGNED, EnumSet.of(PARTNER_AT_RESTAURANT, PICKED_UP));
        map.put(PARTNER_AT_RESTAURANT,     EnumSet.of(PICKED_UP));
        map.put(PICKED_UP,                 EnumSet.of(OUT_FOR_DELIVERY, DELIVERED));
        map.put(OUT_FOR_DELIVERY,          EnumSet.of(DELIVERED));
        map.put(CANCELLING,                EnumSet.of(CANCELLED));
        for (OrderStatus terminal : TERMINAL) {
            map.putIfAbsent(terminal, EnumSet.noneOf(OrderStatus.class));
        }
        ALLOWED_TRANSITIONS = Collections.unmodifiableMap(map);
    }

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    public boolean isCancellableByCustomer() {
        return CANCELLABLE_BY_CUSTOMER.contains(this);
    }

    public boolean canTransitionTo(OrderStatus next) {
        return ALLOWED_TRANSITIONS.getOrDefault(this, Set.of()).contains(next);
    }
}
