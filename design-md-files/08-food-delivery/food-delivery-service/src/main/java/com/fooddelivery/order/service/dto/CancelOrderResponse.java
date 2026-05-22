package com.fooddelivery.order.service.dto;

import com.fooddelivery.order.domain.OrderStatus;

import java.util.UUID;

public record CancelOrderResponse(UUID orderId, OrderStatus status, String message) {}
