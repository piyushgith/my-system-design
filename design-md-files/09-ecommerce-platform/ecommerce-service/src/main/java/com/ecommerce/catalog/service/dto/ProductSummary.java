package com.ecommerce.catalog.service.dto;

import com.ecommerce.catalog.domain.Product;

import java.util.UUID;

public record ProductSummary(
        UUID id,
        String title,
        long priceAmount,
        String currency,
        boolean inStock,
        String imageUrl
) {
    public static ProductSummary from(Product p) {
        return new ProductSummary(
                p.getId(), p.getTitle(), p.getPriceAmount(), p.getCurrency(),
                p.getStockQuantity() > 0, p.getImageUrl());
    }
}
