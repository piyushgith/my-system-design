package com.ecommerce.cart.service.dto;

import java.util.UUID;

public record CartItemResponse(
        UUID productId,
        String title,
        long unitPrice,
        int quantity,
        long lineTotal,
        boolean available
) {}
