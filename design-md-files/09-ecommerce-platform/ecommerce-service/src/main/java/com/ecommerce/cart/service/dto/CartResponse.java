package com.ecommerce.cart.service.dto;

import java.util.List;

public record CartResponse(
        List<CartItemResponse> items,
        String currency,
        long subtotal
) {
    public static CartResponse empty() {
        return new CartResponse(List.of(), "INR", 0L);
    }
}
