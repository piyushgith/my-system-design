package com.fooddelivery.restaurant.service;

import com.fooddelivery.common.exception.NotFoundException;
import com.fooddelivery.restaurant.domain.*;
import com.fooddelivery.restaurant.repository.*;
import com.fooddelivery.restaurant.service.dto.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RestaurantService {

    private static final int MAX_PAGE_SIZE = 50;
    private static final String RESTAURANT_NOT_FOUND = "Restaurant not found";

    private final RestaurantRepository restaurantRepository;
    private final MenuCategoryRepository categoryRepository;
    private final MenuItemRepository itemRepository;

    public Page<RestaurantSummary> browse(String cityId, Boolean isOpen, String cuisine, int page, int size) {
        return restaurantRepository.findByCityAndFilters(
                cityId, RestaurantStatus.APPROVED, isOpen, cuisine,
                PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE))
        ).map(RestaurantSummary::from);
    }

    public RestaurantDetail getById(UUID restaurantId) {
        Restaurant restaurant = restaurantRepository.findById(restaurantId)
                .orElseThrow(() -> new NotFoundException(RESTAURANT_NOT_FOUND));
        return RestaurantDetail.from(restaurant);
    }

    public MenuResponse getMenu(UUID restaurantId) {
        Restaurant restaurant = restaurantRepository.findById(restaurantId)
                .orElseThrow(() -> new NotFoundException(RESTAURANT_NOT_FOUND));

        List<MenuCategory> categories = categoryRepository
                .findByRestaurantIdAndIsActiveTrueOrderByDisplayOrder(restaurantId);

        if (categories.isEmpty()) {
            return new MenuResponse(restaurantId, restaurant.getName(), List.of());
        }

        List<UUID> categoryIds = categories.stream().map(MenuCategory::getId).toList();

        // Single query for all items across all categories — avoids N+1
        Map<UUID, List<MenuItemResponse>> itemsByCategoryId = itemRepository
                .findByCategoryIdInAndIsAvailableTrueOrderByDisplayOrder(categoryIds)
                .stream()
                .collect(Collectors.groupingBy(
                        MenuItem::getCategoryId,
                        Collectors.mapping(MenuItemResponse::from, Collectors.toList())
                ));

        List<MenuCategoryResponse> categoryResponses = categories.stream()
                .map(cat -> MenuCategoryResponse.from(cat, itemsByCategoryId.getOrDefault(cat.getId(), List.of())))
                .toList();

        return new MenuResponse(restaurantId, restaurant.getName(), categoryResponses);
    }

    @Transactional
    public void updateAvailability(UUID restaurantId, boolean isOpen) {
        Restaurant restaurant = restaurantRepository.findById(restaurantId)
                .orElseThrow(() -> new NotFoundException(RESTAURANT_NOT_FOUND));
        restaurant.setOpen(isOpen);
        restaurantRepository.save(restaurant);
    }

    @Transactional
    public void updateMenuItemAvailability(UUID restaurantId, UUID itemId, boolean isAvailable) {
        MenuItem item = itemRepository.findByIdAndRestaurantId(itemId, restaurantId)
                .orElseThrow(() -> new NotFoundException("Menu item not found"));
        item.setAvailable(isAvailable);
        itemRepository.save(item);
    }
}
