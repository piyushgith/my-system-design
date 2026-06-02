package com.fooddelivery.order.controller;

import com.fooddelivery.common.security.AuthenticatedUser;
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
@RequestMapping("/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    public ResponseEntity<PlaceOrderResponse> placeOrder(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody PlaceOrderRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(orderService.placeOrder(principal.userId(), request));
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<OrderDetailResponse> getOrder(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID orderId) {
        return ResponseEntity.ok(orderService.getOrder(orderId, principal.userId()));
    }

    @GetMapping
    public ResponseEntity<Page<OrderSummary>> listOrders(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(orderService.listOrders(principal.userId(), page, size));
    }

    @PostMapping("/{orderId}/cancel")
    public ResponseEntity<CancelOrderResponse> cancelOrder(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID orderId,
            @RequestBody(required = false) CancelRequest request) {
        String reason = request != null ? request.reason() : "Customer request";
        return ResponseEntity.accepted()
                .body(orderService.cancelOrder(orderId, principal.userId(), reason));
    }

    public record CancelRequest(String reason) {}
}
