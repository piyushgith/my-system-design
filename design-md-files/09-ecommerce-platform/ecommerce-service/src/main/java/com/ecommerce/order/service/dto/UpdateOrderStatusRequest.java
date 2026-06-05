package com.ecommerce.order.service.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateOrderStatusRequest(
        @NotBlank String status   // CONFIRMED | SHIPPED | DELIVERED | CANCELLED
) {}
