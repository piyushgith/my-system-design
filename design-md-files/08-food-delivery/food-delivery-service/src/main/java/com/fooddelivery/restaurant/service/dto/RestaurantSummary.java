package com.fooddelivery.restaurant.service.dto;

import com.fooddelivery.restaurant.domain.Restaurant;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record RestaurantSummary(
        UUID id,
        String name,
        List<String> cuisineTypes,
        BigDecimal rating,
        int totalRatings,
        int avgPrepTimeMinutes,
        long minimumOrderAmount,
        boolean isOpen,
        String logoUrl
) {
    public static RestaurantSummary from(Restaurant r) {
        return new RestaurantSummary(
                r.getId(), r.getName(), r.getCuisineTypes(),
                r.getRating(), r.getTotalRatings(), r.getAvgPrepTimeMinutes(),
                r.getMinimumOrderAmount(), r.isOpen(), r.getLogoUrl()
        );
    }
}
