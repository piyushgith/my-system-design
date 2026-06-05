package com.ecommerce.catalog.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "products")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "category_id", nullable = false)
    private UUID categoryId;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    /** Price in minor units (paise/cents). */
    @Column(name = "price_amount", nullable = false)
    private long priceAmount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "stock_quantity", nullable = false)
    private int stockQuantity;

    @Column(name = "image_url", columnDefinition = "text")
    private String imageUrl;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private ProductStatus status;

    @Version
    @Column(nullable = false)
    private int version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (currency == null) currency = "INR";
        if (status == null) status = ProductStatus.ACTIVE;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public boolean isActive() {
        return status == ProductStatus.ACTIVE;
    }

    public boolean hasStock(int quantity) {
        return stockQuantity >= quantity;
    }

    /** Decrements stock; @Version guards against concurrent oversell. */
    public void decrementStock(int quantity) {
        if (!hasStock(quantity)) {
            throw new IllegalStateException("Insufficient stock for product " + id);
        }
        this.stockQuantity -= quantity;
    }
}
