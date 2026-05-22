package com.fooddelivery.user.controller;

import com.fooddelivery.common.security.AuthenticatedUser;
import com.fooddelivery.user.service.UserService;
import com.fooddelivery.user.service.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PostMapping("/auth/send-otp")
    public ResponseEntity<Void> sendOtp(@RequestBody SendOtpRequest request) {
        userService.sendOtp(request.phone());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/auth/verify-otp")
    public ResponseEntity<AuthResponse> verifyOtp(@RequestBody VerifyOtpRequest request) {
        return ResponseEntity.ok(userService.verifyOtp(request.phone(), request.otp()));
    }

    @GetMapping("/users/me")
    public ResponseEntity<UserProfileResponse> getProfile(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return ResponseEntity.ok(userService.getProfile(principal.userId()));
    }

    @PatchMapping("/users/me")
    public ResponseEntity<UserProfileResponse> updateProfile(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody UpdateProfileRequest request) {
        return ResponseEntity.ok(userService.updateProfile(principal.userId(), request));
    }

    @GetMapping("/users/me/addresses")
    public ResponseEntity<List<AddressResponse>> listAddresses(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return ResponseEntity.ok(userService.listAddresses(principal.userId()));
    }

    @PostMapping("/users/me/addresses")
    public ResponseEntity<AddressResponse> addAddress(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody AddAddressRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(userService.addAddress(principal.userId(), request));
    }

    @DeleteMapping("/users/me/addresses/{addressId}")
    public ResponseEntity<Void> deleteAddress(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID addressId) {
        userService.deleteAddress(principal.userId(), addressId);
        return ResponseEntity.noContent().build();
    }
}
