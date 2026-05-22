package com.fooddelivery.restaurant.repository;

import com.fooddelivery.restaurant.domain.Restaurant;
import com.fooddelivery.restaurant.domain.RestaurantStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface RestaurantRepository extends JpaRepository<Restaurant, UUID> {

    @Query("""
            SELECT r FROM Restaurant r
            WHERE r.cityId = :cityId
              AND r.status = :status
              AND (:isOpen IS NULL OR r.isOpen = :isOpen)
              AND (:cuisine IS NULL OR :cuisine MEMBER OF r.cuisineTypes)
            ORDER BY r.rating DESC
            """)
    Page<Restaurant> findByCityAndFilters(
            @Param("cityId") String cityId,
            @Param("status") RestaurantStatus status,
            @Param("isOpen") Boolean isOpen,
            @Param("cuisine") String cuisine,
            Pageable pageable);
}
