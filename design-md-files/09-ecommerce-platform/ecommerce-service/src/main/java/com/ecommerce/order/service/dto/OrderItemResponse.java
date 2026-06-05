package com.ecommerce.order.service.dto;

import com.ecommerce.order.domain.OrderItem;

import java.util.UUID;

public record OrderItemResponse(
        UUID productId,
        String title,
        int quantity,
        long unitPrice,
        long lineTotal
) {
    public static OrderItemResponse from(OrderItem item) {
        return new OrderItemResponse(
                item.getProductId(), item.getTitleSnapshot(),
                item.getQuantity(), item.getUnitPrice(), item.getTotalPrice());
    }
}
