package com.fooddelivery.user.service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record SendOtpRequest(
        @NotBlank @Pattern(regexp = "^\\+?[0-9]{10,15}$") String phone
) {}
