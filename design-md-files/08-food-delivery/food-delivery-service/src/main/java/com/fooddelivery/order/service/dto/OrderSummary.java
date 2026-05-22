package com.fooddelivery.order.service.dto;

import com.fooddelivery.order.domain.Order;
import com.fooddelivery.order.domain.OrderStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record OrderSummary(
        UUID id,
        UUID restaurantId,
        OrderStatus status,
        long totalAmount,
        String currency,
        LocalDateTime createdAt
) {
    public static OrderSummary from(Order o) {
        return new OrderSummary(o.getId(), o.getRestaurantId(), o.getStatus(),
                o.getTotalAmount(), o.getSubtotalCurrency(), o.getCreatedAt());
    }
}
