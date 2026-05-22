package com.fooddelivery.restaurant.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "restaurants")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Restaurant {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "cuisine_types", columnDefinition = "text[]")
    private List<String> cuisineTypes;

    @Column(nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    private RestaurantStatus status;

    @Column(name = "is_open", nullable = false)
    private boolean isOpen;

    @Column(name = "city_id", nullable = false, length = 50)
    private String cityId;

    @Column(nullable = false, precision = 9, scale = 6)
    private BigDecimal latitude;

    @Column(nullable = false, precision = 9, scale = 6)
    private BigDecimal longitude;

    @Column(name = "delivery_radius_meters", nullable = false)
    private int deliveryRadiusMeters;

    @Column(name = "minimum_order_amount", nullable = false)
    private long minimumOrderAmount;

    @Column(name = "avg_prep_time_minutes", nullable = false)
    private int avgPrepTimeMinutes;

    @Column(nullable = false, precision = 3, scale = 2)
    private BigDecimal rating;

    @Column(name = "total_ratings", nullable = false)
    private int totalRatings;

    @Column(name = "commission_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal commissionRate;

    @Column(name = "logo_url", length = 500)
    private String logoUrl;

    @Column(name = "onboarded_at")
    private LocalDateTime onboardedAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
        if (status == null) status = RestaurantStatus.PENDING_APPROVAL;
    }

    public boolean isServicingOrders() {
        return status == RestaurantStatus.APPROVED && isOpen;
    }
}
