package com.fooddelivery.restaurant.repository;

import com.fooddelivery.restaurant.domain.MenuItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MenuItemRepository extends JpaRepository<MenuItem, UUID> {
    List<MenuItem> findByCategoryIdAndIsAvailableTrueOrderByDisplayOrder(UUID categoryId);
    Optional<MenuItem> findByIdAndRestaurantId(UUID itemId, UUID restaurantId);
}
