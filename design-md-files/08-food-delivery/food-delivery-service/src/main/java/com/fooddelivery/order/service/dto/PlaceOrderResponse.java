package com.fooddelivery.order.service.dto;

import com.fooddelivery.order.domain.Order;
import com.fooddelivery.order.domain.OrderStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record PlaceOrderResponse(
        UUID orderId,
        OrderStatus status,
        long totalAmount,
        String currency,
        LocalDateTime estimatedDeliveryTime,
        String trackingUrl
) {
    public static PlaceOrderResponse from(Order o) {
        return new PlaceOrderResponse(
                o.getId(), o.getStatus(), o.getTotalAmount(), o.getSubtotalCurrency(),
                o.getEstimatedDeliveryTime(),
                "/v1/orders/" + o.getId() + "/tracking"
        );
    }
}
