package com.ecommerce.catalog.service.dto;

import jakarta.validation.constraints.*;

import java.util.UUID;

public record CreateProductRequest(
        @NotNull UUID categoryId,
        @NotBlank @Size(max = 500) String title,
        String description,
        @PositiveOrZero long priceAmount,
        @Size(min = 3, max = 3) String currency,
        @PositiveOrZero int stockQuantity,
        String imageUrl
) {}
