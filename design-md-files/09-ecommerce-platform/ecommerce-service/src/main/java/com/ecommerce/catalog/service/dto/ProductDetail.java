package com.ecommerce.catalog.service.dto;

import com.ecommerce.catalog.domain.Product;

import java.util.UUID;

public record ProductDetail(
        UUID id,
        UUID categoryId,
        String title,
        String description,
        long priceAmount,
        String currency,
        int stockQuantity,
        String imageUrl,
        String status
) {
    public static ProductDetail from(Product p) {
        return new ProductDetail(
                p.getId(), p.getCategoryId(), p.getTitle(), p.getDescription(),
                p.getPriceAmount(), p.getCurrency(), p.getStockQuantity(),
                p.getImageUrl(), p.getStatus().name());
    }
}
