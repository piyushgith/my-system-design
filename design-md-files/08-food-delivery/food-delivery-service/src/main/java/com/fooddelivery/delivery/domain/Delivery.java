package com.fooddelivery.delivery.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "deliveries")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Delivery {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "order_id", nullable = false, unique = true)
    private UUID orderId;

    @Column(name = "partner_id", nullable = false)
    private UUID partnerId;

    @Column(name = "restaurant_lat", nullable = false, precision = 9, scale = 6)
    private BigDecimal restaurantLat;

    @Column(name = "restaurant_lng", nullable = false, precision = 9, scale = 6)
    private BigDecimal restaurantLng;

    @Column(name = "customer_lat", nullable = false, precision = 9, scale = 6)
    private BigDecimal customerLat;

    @Column(name = "customer_lng", nullable = false, precision = 9, scale = 6)
    private BigDecimal customerLng;

    @Column(nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    private DeliveryStatus status;

    @Column(name = "estimated_pickup_time")
    private LocalDateTime estimatedPickupTime;

    @Column(name = "actual_pickup_time")
    private LocalDateTime actualPickupTime;

    @Column(name = "estimated_delivery_time")
    private LocalDateTime estimatedDeliveryTime;

    @Column(name = "actual_delivery_time")
    private LocalDateTime actualDeliveryTime;

    @Column(name = "distance_km", precision = 7, scale = 3)
    private BigDecimal distanceKm;

    @Column(name = "delivery_fee_amount", nullable = false)
    private long deliveryFeeAmount;

    @Column(name = "partner_earning_amount", nullable = false)
    private long partnerEarningAmount;

    @Column(name = "failure_reason", length = 300)
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (status == null) status = DeliveryStatus.ASSIGNED;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
