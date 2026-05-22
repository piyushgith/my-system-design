package com.fooddelivery.order.domain;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "order_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "menu_item_id", nullable = false)
    private UUID menuItemId;

    @Column(name = "menu_item_name", nullable = false, length = 200)
    private String menuItemName;

    @Column(name = "unit_price_amount", nullable = false)
    private long unitPriceAmount;

    @Column(name = "unit_price_currency", nullable = false, length = 3)
    private String unitPriceCurrency;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "total_price_amount", nullable = false)
    private long totalPriceAmount;

    @Column(length = 300)
    private String customizations;

    @PrePersist
    void onCreate() {
        if (unitPriceCurrency == null) unitPriceCurrency = "INR";
    }
}
