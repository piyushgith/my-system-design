package com.fooddelivery.restaurant.repository;

import com.fooddelivery.restaurant.domain.MenuCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MenuCategoryRepository extends JpaRepository<MenuCategory, UUID> {
    List<MenuCategory> findByRestaurantIdAndIsActiveTrueOrderByDisplayOrder(UUID restaurantId);
}
