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
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RestaurantService {

    private final RestaurantRepository restaurantRepository;
    private final MenuCategoryRepository categoryRepository;
    private final MenuItemRepository itemRepository;

    public Page<RestaurantSummary> browse(String cityId, Boolean isOpen, String cuisine, int page, int size) {
        return restaurantRepository.findByCityAndFilters(
                cityId, RestaurantStatus.APPROVED, isOpen, cuisine,
                PageRequest.of(page, Math.min(size, 50))
        ).map(RestaurantSummary::from);
    }

    public RestaurantDetail getById(UUID restaurantId) {
        Restaurant restaurant = restaurantRepository.findById(restaurantId)
                .orElseThrow(() -> new NotFoundException("Restaurant not found"));
        return RestaurantDetail.from(restaurant);
    }

    public MenuResponse getMenu(UUID restaurantId) {
        Restaurant restaurant = restaurantRepository.findById(restaurantId)
                .orElseThrow(() -> new NotFoundException("Restaurant not found"));

        List<MenuCategoryResponse> categories = categoryRepository
                .findByRestaurantIdAndIsActiveTrueOrderByDisplayOrder(restaurantId)
                .stream()
                .map(cat -> {
                    List<MenuItemResponse> items = itemRepository
                            .findByCategoryIdAndIsAvailableTrueOrderByDisplayOrder(cat.getId())
                            .stream().map(MenuItemResponse::from).toList();
                    return MenuCategoryResponse.from(cat, items);
                })
                .toList();

        return new MenuResponse(restaurantId, restaurant.getName(), categories);
    }

    @Transactional
    public void updateAvailability(UUID restaurantId, boolean isOpen) {
        Restaurant restaurant = restaurantRepository.findById(restaurantId)
                .orElseThrow(() -> new NotFoundException("Restaurant not found"));
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
