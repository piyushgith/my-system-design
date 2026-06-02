package com.fooddelivery.order.controller;

import com.fooddelivery.common.security.AuthenticatedUser;
import com.fooddelivery.order.domain.OrderStatus;
import com.fooddelivery.order.service.OrderService;
import com.fooddelivery.order.service.dto.OrderDetailResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/v1/restaurant/orders")
@RequiredArgsConstructor
@PreAuthorize("hasRole('RESTAURANT_OWNER')")
public class RestaurantOrderController {

    private final OrderService orderService;

    @PutMapping("/{orderId}/accept")
    public ResponseEntity<Void> acceptOrder(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID orderId,
            @RequestParam(defaultValue = "25") int estimatedPrepMinutes) {
        orderService.acceptOrder(orderId, principal.userId(), estimatedPrepMinutes);
        return ResponseEntity.ok().build();
    }

    @PutMapping("/{orderId}/reject")
    public ResponseEntity<Void> rejectOrder(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID orderId,
            @RequestParam(defaultValue = "Unavailable") String reason) {
        orderService.rejectOrder(orderId, principal.userId(), reason);
        return ResponseEntity.ok().build();
    }

    @PutMapping("/{orderId}/ready")
    public ResponseEntity<Void> markReady(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID orderId) {
        orderService.markFoodReady(orderId, principal.userId());
        return ResponseEntity.ok().build();
    }

    @GetMapping
    public ResponseEntity<Page<OrderDetailResponse>> listOrders(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam UUID restaurantId,
            @RequestParam(defaultValue = "RESTAURANT_NOTIFIED") OrderStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(orderService.getRestaurantOrders(restaurantId, principal.userId(), status, page, size));
    }
}
