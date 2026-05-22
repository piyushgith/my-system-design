package com.fooddelivery.restaurant.service.dto;

import com.fooddelivery.restaurant.domain.MenuItem;

import java.util.List;
import java.util.UUID;

public record MenuItemResponse(
        UUID id,
        String name,
        String description,
        long priceAmount,
        String priceCurrency,
        Long discountedPriceAmount,
        boolean isVegetarian,
        boolean isVegan,
        int prepTimeMinutes,
        List<String> tags,
        List<String> allergens,
        String imageUrl
) {
    public static MenuItemResponse from(MenuItem m) {
        return new MenuItemResponse(
                m.getId(), m.getName(), m.getDescription(),
                m.getPriceAmount(), m.getPriceCurrency(), m.getDiscountedPriceAmount(),
                m.isVegetarian(), m.isVegan(), m.getPrepTimeMinutes(),
                m.getTags(), m.getAllergens(), m.getImageUrl()
        );
    }
}
