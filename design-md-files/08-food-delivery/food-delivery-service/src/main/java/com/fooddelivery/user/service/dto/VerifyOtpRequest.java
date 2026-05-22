package com.fooddelivery.user.service.dto;

import jakarta.validation.constraints.NotBlank;

public record VerifyOtpRequest(@NotBlank String phone, @NotBlank String otp) {}
