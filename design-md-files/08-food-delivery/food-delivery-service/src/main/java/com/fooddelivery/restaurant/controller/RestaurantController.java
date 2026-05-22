package com.fooddelivery.restaurant.controller;

import com.fooddelivery.restaurant.service.RestaurantService;
import com.fooddelivery.restaurant.service.dto.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class RestaurantController {

    private final RestaurantService restaurantService;

    @GetMapping("/restaurants")
    public ResponseEntity<Page<RestaurantSummary>> browse(
            @RequestParam String cityId,
            @RequestParam(defaultValue = "true") Boolean isOpen,
            @RequestParam(required = false) String cuisine,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(restaurantService.browse(cityId, isOpen, cuisine, page, size));
    }

    @GetMapping("/restaurants/{restaurantId}")
    public ResponseEntity<RestaurantDetail> getById(@PathVariable UUID restaurantId) {
        return ResponseEntity.ok(restaurantService.getById(restaurantId));
    }

    @GetMapping("/restaurants/{restaurantId}/menu")
    public ResponseEntity<MenuResponse> getMenu(@PathVariable UUID restaurantId) {
        return ResponseEntity.ok(restaurantService.getMenu(restaurantId));
    }

    // Restaurant partner: update availability
    @PutMapping("/restaurant/availability")
    public ResponseEntity<Void> updateAvailability(
            @RequestParam UUID restaurantId,
            @RequestParam boolean isOpen) {
        restaurantService.updateAvailability(restaurantId, isOpen);
        return ResponseEntity.ok().build();
    }

    // Restaurant partner: toggle menu item availability
    @PutMapping("/restaurant/menu/items/{itemId}/availability")
    public ResponseEntity<Void> toggleItemAvailability(
            @RequestParam UUID restaurantId,
            @PathVariable UUID itemId,
            @RequestParam boolean isAvailable) {
        restaurantService.updateMenuItemAvailability(restaurantId, itemId, isAvailable);
        return ResponseEntity.ok().build();
    }
}
