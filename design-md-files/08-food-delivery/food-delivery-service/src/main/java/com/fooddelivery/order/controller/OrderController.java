package com.fooddelivery.order.controller;

import com.fooddelivery.common.security.AuthenticatedUser;
import com.fooddelivery.order.domain.OrderStatus;
import com.fooddelivery.order.service.OrderService;
import com.fooddelivery.order.service.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping("/orders")
    public ResponseEntity<PlaceOrderResponse> placeOrder(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody PlaceOrderRequest request) {
        PlaceOrderResponse response = orderService.placeOrder(principal.userId(), request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @GetMapping("/orders/{orderId}")
    public ResponseEntity<OrderDetailResponse> getOrder(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID orderId) {
        return ResponseEntity.ok(orderService.getOrder(orderId, principal.userId()));
    }

    @GetMapping("/orders")
    public ResponseEntity<Page<OrderSummary>> listOrders(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(orderService.listOrders(principal.userId(), page, size));
    }

    @PostMapping("/orders/{orderId}/cancel")
    public ResponseEntity<CancelOrderResponse> cancelOrder(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID orderId,
            @RequestBody(required = false) CancelRequest request) {
        String reason = request != null ? request.reason() : "Customer request";
        return ResponseEntity.accepted().body(orderService.cancelOrder(orderId, principal.userId(), reason));
    }

    // Restaurant partner endpoints
    @PutMapping("/restaurant/orders/{orderId}/accept")
    public ResponseEntity<Void> acceptOrder(
            @PathVariable UUID orderId,
            @RequestParam(defaultValue = "25") int estimatedPrepMinutes) {
        orderService.acceptOrder(orderId, estimatedPrepMinutes);
        return ResponseEntity.ok().build();
    }

    @PutMapping("/restaurant/orders/{orderId}/reject")
    public ResponseEntity<Void> rejectOrder(
            @PathVariable UUID orderId,
            @RequestParam(defaultValue = "Unavailable") String reason) {
        orderService.rejectOrder(orderId, reason);
        return ResponseEntity.ok().build();
    }

    @PutMapping("/restaurant/orders/{orderId}/ready")
    public ResponseEntity<Void> markReady(@PathVariable UUID orderId) {
        orderService.markFoodReady(orderId);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/restaurant/orders")
    public ResponseEntity<Page<OrderDetailResponse>> getRestaurantOrders(
            @RequestParam UUID restaurantId,
            @RequestParam(defaultValue = "RESTAURANT_NOTIFIED") OrderStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(orderService.getRestaurantOrders(restaurantId, status, page, size));
    }

    public record CancelRequest(String reason) {}
}
