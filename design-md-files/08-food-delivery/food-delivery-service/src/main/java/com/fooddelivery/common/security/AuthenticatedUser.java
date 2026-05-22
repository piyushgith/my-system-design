package com.fooddelivery.common.security;

import java.util.UUID;

public record AuthenticatedUser(UUID userId, String role) {}
