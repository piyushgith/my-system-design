package com.ecommerce.user.service.dto;

import com.ecommerce.user.domain.User;

import java.util.UUID;

public record UserProfileResponse(UUID id, String name, String email, String role) {

    public static UserProfileResponse from(User user) {
        return new UserProfileResponse(
                user.getId(), user.getName(), user.getEmail(), user.getRole().name());
    }
}
