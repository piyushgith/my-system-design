package com.test.ride.sharing.service.web;

import com.test.ride.sharing.service.shared.UserRole;

import java.util.UUID;

public record AuthContext(UUID userId, UserRole role) {
}
