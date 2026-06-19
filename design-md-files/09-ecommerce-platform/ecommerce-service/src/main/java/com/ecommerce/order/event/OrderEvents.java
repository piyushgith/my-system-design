package com.ecommerce.order.event;

import java.util.UUID;

/** Domain event payloads emitted by the order module (recorded to the outbox). */
public final class OrderEvents {

    public static final String AGGREGATE_TYPE = "ORDER";
    public static final String ORDER_PLACED = "OrderPlaced";
    public static final String ORDER_CANCELLED = "OrderCancelled";

    private OrderEvents() {
    }

    public record OrderPlaced(UUID orderId, UUID buyerId, long totalAmount, String currency) {}

    public record OrderCancelled(UUID orderId, UUID buyerId) {}
}
