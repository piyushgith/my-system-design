package com.fooddelivery.user.service.dto;

import java.util.UUID;

public record AuthResponse(String accessToken, UUID userId, String name, String phone) {}
