package com.ecommerce.order.service.dto;

import com.ecommerce.order.domain.Order;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record OrderResponse(
        UUID id,
        UUID buyerId,
        String status,
        long totalAmount,
        String currency,
        String paymentMethod,
        String shippingAddress,
        List<OrderItemResponse> items,
        LocalDateTime createdAt
) {
    public static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getBuyerId(),
                order.getStatus().name(),
                order.getTotalAmount(),
                order.getCurrency(),
                order.getPaymentMethod(),
                order.getShippingAddress(),
                order.getItems().stream().map(OrderItemResponse::from).toList(),
                order.getCreatedAt());
    }
}
