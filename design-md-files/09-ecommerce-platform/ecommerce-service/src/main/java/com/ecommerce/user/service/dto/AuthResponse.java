package com.ecommerce.user.service.dto;

import java.util.UUID;

public record AuthResponse(String token, String refreshToken, UUID userId, String name, String role) {}
