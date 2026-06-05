package com.ecommerce.order.controller;

import com.ecommerce.order.domain.OrderStatus;
import com.ecommerce.order.service.OrderService;
import com.ecommerce.order.service.dto.OrderResponse;
import com.ecommerce.order.service.dto.OrderSummary;
import com.ecommerce.order.service.dto.UpdateOrderStatusRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/v1/admin/orders")
@RequiredArgsConstructor
public class AdminOrderController {

    private final OrderService orderService;

    @GetMapping
    public ResponseEntity<Page<OrderSummary>> listOrders(
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(orderService.listAllOrders(status, page, size));
    }

    @PatchMapping("/{orderId}/status")
    public ResponseEntity<OrderResponse> updateStatus(
            @PathVariable UUID orderId,
            @Valid @RequestBody UpdateOrderStatusRequest request) {
        return ResponseEntity.ok(orderService.updateStatus(orderId, request.status()));
    }
}
