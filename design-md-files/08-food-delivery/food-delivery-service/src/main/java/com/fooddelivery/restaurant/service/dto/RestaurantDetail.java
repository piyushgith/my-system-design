package com.fooddelivery.restaurant.service.dto;

import com.fooddelivery.restaurant.domain.Restaurant;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record RestaurantDetail(
        UUID id,
        String name,
        String description,
        List<String> cuisineTypes,
        BigDecimal rating,
        int totalRatings,
        int avgPrepTimeMinutes,
        long minimumOrderAmount,
        boolean isOpen,
        String cityId,
        BigDecimal latitude,
        BigDecimal longitude,
        int deliveryRadiusMeters,
        String logoUrl
) {
    public static RestaurantDetail from(Restaurant r) {
        return new RestaurantDetail(
                r.getId(), r.getName(), r.getDescription(), r.getCuisineTypes(),
                r.getRating(), r.getTotalRatings(), r.getAvgPrepTimeMinutes(),
                r.getMinimumOrderAmount(), r.isOpen(),
                r.getCityId(), r.getLatitude(), r.getLongitude(),
                r.getDeliveryRadiusMeters(), r.getLogoUrl()
        );
    }
}
