package com.ecommerce.catalog.service.dto;

import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** All fields optional — only non-null fields are applied (partial update). */
public record UpdateProductRequest(
        @Size(max = 500) String title,
        String description,
        @PositiveOrZero Long priceAmount,
        @PositiveOrZero Integer stockQuantity,
        String imageUrl,
        String status
) {}
