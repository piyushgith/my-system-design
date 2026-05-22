package com.fooddelivery.order.service.dto;

import com.fooddelivery.order.domain.Order;
import com.fooddelivery.order.domain.OrderStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record OrderDetailResponse(
        UUID id,
        UUID customerId,
        UUID restaurantId,
        OrderStatus status,
        List<OrderItemDetail> items,
        long subtotalAmount,
        long deliveryFeeAmount,
        long discountAmount,
        long totalAmount,
        String currency,
        String paymentMethod,
        LocalDateTime estimatedDeliveryTime,
        LocalDateTime actualDeliveryTime,
        String specialInstructions,
        LocalDateTime createdAt
) {
    public static OrderDetailResponse from(Order o) {
        List<OrderItemDetail> items = o.getItems().stream()
                .map(i -> new OrderItemDetail(i.getMenuItemId(), i.getMenuItemName(),
                        i.getUnitPriceAmount(), i.getQuantity(), i.getTotalPriceAmount(), i.getCustomizations()))
                .toList();
        return new OrderDetailResponse(
                o.getId(), o.getCustomerId(), o.getRestaurantId(), o.getStatus(),
                items, o.getSubtotalAmount(), o.getDeliveryFeeAmount(), o.getDiscountAmount(),
                o.getTotalAmount(), o.getSubtotalCurrency(), o.getPaymentMethod(),
                o.getEstimatedDeliveryTime(), o.getActualDeliveryTime(),
                o.getSpecialInstructions(), o.getCreatedAt()
        );
    }

    public record OrderItemDetail(UUID menuItemId, String name, long unitPrice, int quantity, long totalPrice, String customizations) {}
}
