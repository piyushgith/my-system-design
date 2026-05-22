package com.fooddelivery.restaurant.service.dto;

import java.util.List;
import java.util.UUID;

public record MenuResponse(UUID restaurantId, String restaurantName, List<MenuCategoryResponse> categories) {}
