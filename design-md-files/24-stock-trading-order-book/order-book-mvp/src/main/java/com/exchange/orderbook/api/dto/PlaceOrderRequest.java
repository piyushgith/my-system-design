package com.exchange.orderbook.api.dto;

import com.exchange.orderbook.domain.OrderSide;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record PlaceOrderRequest(
    @NotNull(message = "side is required")
    OrderSide side,

    @NotNull(message = "price is required")
    @DecimalMin(value = "0.0001", message = "price must be positive")
    BigDecimal price,

    @NotNull(message = "quantity is required")
    @Min(value = 1, message = "quantity must be at least 1")
    Integer quantity,

    String clientOrderId
) {}
