package com.ecommerce.cart.controller;

import com.ecommerce.cart.service.CartService;
import com.ecommerce.cart.service.dto.AddCartItemRequest;
import com.ecommerce.cart.service.dto.CartResponse;
import com.ecommerce.cart.service.dto.UpdateCartItemRequest;
import com.ecommerce.common.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/v1/cart")
@RequiredArgsConstructor
@Tag(name = "Cart", description = "Redis-backed shopping cart")
public class CartController {

    private final CartService cartService;

    @GetMapping
    public ResponseEntity<CartResponse> getCart(@AuthenticationPrincipal AuthenticatedUser principal) {
        return ResponseEntity.ok(cartService.getCart(principal.userId()));
    }

    @PostMapping("/items")
    public ResponseEntity<CartResponse> addItem(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody AddCartItemRequest request) {
        return ResponseEntity.ok(cartService.addItem(principal.userId(), request));
    }

    @PutMapping("/items/{productId}")
    public ResponseEntity<CartResponse> updateItem(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID productId,
            @Valid @RequestBody UpdateCartItemRequest request) {
        return ResponseEntity.ok(cartService.updateItem(principal.userId(), productId, request.quantity()));
    }

    @DeleteMapping("/items/{productId}")
    public ResponseEntity<CartResponse> removeItem(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID productId) {
        return ResponseEntity.ok(cartService.updateItem(principal.userId(), productId, 0));
    }

    @DeleteMapping
    public ResponseEntity<Void> clearCart(@AuthenticationPrincipal AuthenticatedUser principal) {
        cartService.clear(principal.userId());
        return ResponseEntity.noContent().build();
    }
}
