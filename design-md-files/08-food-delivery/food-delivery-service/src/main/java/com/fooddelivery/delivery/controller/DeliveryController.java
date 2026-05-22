package com.fooddelivery.delivery.controller;

import com.fooddelivery.common.security.AuthenticatedUser;
import com.fooddelivery.delivery.service.DeliveryService;
import com.fooddelivery.delivery.service.dto.DeliveryResponse;
import com.fooddelivery.delivery.service.dto.PartnerLocationResponse;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.UUID;

@RestController
@RequestMapping("/v1/delivery")
@RequiredArgsConstructor
public class DeliveryController {

    private final DeliveryService deliveryService;

    /** Partner goes online/offline */
    @PutMapping("/status")
    public ResponseEntity<Void> setStatus(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam boolean online) {
        deliveryService.setOnlineStatus(principal.userId(), online);
        return ResponseEntity.ok().build();
    }

    /** Partner sends GPS location — called every 5 seconds */
    @PutMapping("/location")
    public ResponseEntity<Void> updateLocation(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam @NotNull BigDecimal lat,
            @RequestParam @NotNull BigDecimal lng) {
        deliveryService.updateLocation(principal.userId(), lat, lng);
        return ResponseEntity.noContent().build();
    }

    /** Admin / ops: manually assign partner to order (MVP dispatch) */
    @PostMapping("/assignments")
    public ResponseEntity<DeliveryResponse> assign(
            @RequestParam UUID orderId,
            @RequestParam UUID partnerId) {
        return ResponseEntity.ok(deliveryService.assignPartner(orderId, partnerId));
    }

    /** Partner picks up food from restaurant */
    @PutMapping("/trips/{orderId}/pickup")
    public ResponseEntity<Void> pickup(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID orderId) {
        deliveryService.markPickedUp(orderId, principal.userId());
        return ResponseEntity.ok().build();
    }

    /** Partner marks order delivered */
    @PutMapping("/trips/{orderId}/delivered")
    public ResponseEntity<Void> delivered(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID orderId) {
        deliveryService.markDelivered(orderId, principal.userId());
        return ResponseEntity.ok().build();
    }

    /** Customer polls driver location for tracking */
    @GetMapping("/orders/{orderId}/location")
    public ResponseEntity<PartnerLocationResponse> driverLocation(@PathVariable UUID orderId) {
        return ResponseEntity.ok(deliveryService.getDriverLocation(orderId));
    }
}
