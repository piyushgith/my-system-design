package com.fooddelivery.order.service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record PlaceOrderRequest(
        @NotNull UUID restaurantId,
        @NotNull UUID deliveryAddressId,
        @NotEmpty List<OrderItemRequest> items,
        @NotBlank String paymentMethod,
        String couponCode,
        @Size(max = 200) String specialInstructions,
        @NotBlank String idempotencyKey
) {}
