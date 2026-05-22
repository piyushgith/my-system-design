package com.fooddelivery.restaurant.service.dto;

import com.fooddelivery.restaurant.domain.MenuCategory;

import java.util.List;
import java.util.UUID;

public record MenuCategoryResponse(UUID id, String name, int displayOrder, List<MenuItemResponse> items) {
    public static MenuCategoryResponse from(MenuCategory c, List<MenuItemResponse> items) {
        return new MenuCategoryResponse(c.getId(), c.getName(), c.getDisplayOrder(), items);
    }
}
