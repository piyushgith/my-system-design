package com.ecommerce.order.domain;

import java.util.Set;

/**
 * MVP order lifecycle (COD only — no payment authorization step).
 * PLACED → CONFIRMED → SHIPPED → DELIVERED, with CANCELLED reachable
 * from the early states.
 */
public enum OrderStatus {
    PLACED,
    CONFIRMED,
    SHIPPED,
    DELIVERED,
    CANCELLED;

    private static final java.util.Map<OrderStatus, Set<OrderStatus>> TRANSITIONS = java.util.Map.of(
            PLACED,    Set.of(CONFIRMED, CANCELLED),
            CONFIRMED, Set.of(SHIPPED, CANCELLED),
            SHIPPED,   Set.of(DELIVERED),
            DELIVERED, Set.of(),
            CANCELLED, Set.of()
    );

    public boolean canTransitionTo(OrderStatus target) {
        return TRANSITIONS.getOrDefault(this, Set.of()).contains(target);
    }

    public boolean isCancellableByCustomer() {
        return this == PLACED || this == CONFIRMED;
    }
}
