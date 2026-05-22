package com.fooddelivery.user.service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record AddAddressRequest(
        @NotBlank String label,
        @NotBlank String fullAddress,
        @NotBlank String city,
        @NotBlank String pinCode,
        String country,
        @NotNull BigDecimal latitude,
        @NotNull BigDecimal longitude,
        String landmark,
        boolean isDefault
) {}
