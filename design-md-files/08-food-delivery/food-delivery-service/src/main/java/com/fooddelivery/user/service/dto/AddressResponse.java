package com.fooddelivery.user.service.dto;

import com.fooddelivery.user.domain.Address;

import java.math.BigDecimal;
import java.util.UUID;

public record AddressResponse(
        UUID id,
        String label,
        String fullAddress,
        String city,
        String pinCode,
        BigDecimal latitude,
        BigDecimal longitude,
        String landmark,
        boolean isDefault
) {
    public static AddressResponse from(Address a) {
        return new AddressResponse(
                a.getId(), a.getLabel(), a.getFullAddress(),
                a.getCity(), a.getPinCode(),
                a.getLatitude(), a.getLongitude(),
                a.getLandmark(), a.isDefault()
        );
    }
}
