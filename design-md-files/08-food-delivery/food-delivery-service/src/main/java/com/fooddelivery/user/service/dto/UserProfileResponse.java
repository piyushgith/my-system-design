package com.fooddelivery.user.service.dto;

import com.fooddelivery.user.domain.User;

import java.util.UUID;

public record UserProfileResponse(UUID id, String name, String email, String phone, int loyaltyPoints) {
    public static UserProfileResponse from(User u) {
        return new UserProfileResponse(u.getId(), u.getName(), u.getEmail(), u.getPhone(), u.getLoyaltyPoints());
    }
}
