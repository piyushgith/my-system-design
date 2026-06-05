package com.ecommerce.order.controller;

import com.ecommerce.common.security.AuthenticatedUser;
import com.ecommerce.order.service.OrderService;
import com.ecommerce.order.service.dto.CheckoutRequest;
import com.ecommerce.order.service.dto.OrderResponse;
import com.ecommerce.order.service.dto.OrderSummary;
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

    @PostMapping("/checkout")
    public ResponseEntity<OrderResponse> checkout(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody CheckoutRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(orderService.checkout(principal.userId(), request));
    }

    @GetMapping
    public ResponseEntity<Page<OrderSummary>> listOrders(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(orderService.listOrders(principal.userId(), page, size));
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<OrderResponse> getOrder(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID orderId) {
        return ResponseEntity.ok(orderService.getOrder(orderId, principal.userId()));
    }

    @PostMapping("/{orderId}/cancel")
    public ResponseEntity<OrderResponse> cancelOrder(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID orderId) {
        return ResponseEntity.ok(orderService.cancelOrder(orderId, principal.userId()));
    }
}
