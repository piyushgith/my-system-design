package com.ecommerce.order.service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CheckoutRequest(
        @NotBlank @Size(max = 2000) String shippingAddress,
        @NotBlank @Size(max = 100) String idempotencyKey
) {}
