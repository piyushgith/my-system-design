package com.ecommerce.order.service.dto;

import com.ecommerce.order.domain.Order;

import java.time.LocalDateTime;
import java.util.UUID;

public record OrderSummary(
        UUID id,
        String status,
        long totalAmount,
        String currency,
        int itemCount,
        LocalDateTime createdAt
) {
    public static OrderSummary from(Order order) {
        return new OrderSummary(
                order.getId(),
                order.getStatus().name(),
                order.getTotalAmount(),
                order.getCurrency(),
                order.getItems().size(),
                order.getCreatedAt());
    }
}
