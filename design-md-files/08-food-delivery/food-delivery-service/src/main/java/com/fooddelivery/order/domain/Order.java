package com.fooddelivery.order.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "orders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "restaurant_id", nullable = false)
    private UUID restaurantId;

    @Column(name = "delivery_address_id", nullable = false)
    private UUID deliveryAddressId;

    @Column(nullable = false, length = 40)
    @Enumerated(EnumType.STRING)
    private OrderStatus status;

    @Column(name = "saga_state", length = 40)
    private String sagaState;

    @Column(name = "subtotal_amount", nullable = false)
    private long subtotalAmount;

    @Column(name = "subtotal_currency", nullable = false, length = 3)
    private String subtotalCurrency;

    @Column(name = "delivery_fee_amount", nullable = false)
    private long deliveryFeeAmount;

    @Column(name = "discount_amount", nullable = false)
    private long discountAmount;

    @Column(name = "platform_fee_amount", nullable = false)
    private long platformFeeAmount;

    @Column(name = "total_amount", nullable = false)
    private long totalAmount;

    @Column(name = "payment_method", nullable = false, length = 20)
    private String paymentMethod;

    @Column(name = "payment_id")
    private UUID paymentId;

    @Column(name = "delivery_partner_id")
    private UUID deliveryPartnerId;

    @Column(name = "estimated_delivery_time")
    private LocalDateTime estimatedDeliveryTime;

    @Column(name = "actual_delivery_time")
    private LocalDateTime actualDeliveryTime;

    @Column(name = "special_instructions", length = 200)
    private String specialInstructions;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 100)
    private String idempotencyKey;

    @Column(name = "cancelled_by", length = 20)
    private String cancelledBy;

    @Column(name = "cancellation_reason", length = 300)
    private String cancellationReason;

    @Column(name = "city_id", nullable = false, length = 50)
    private String cityId;

    @Version
    @Column(nullable = false)
    private int version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "order_id")
    @Builder.Default
    private List<OrderItem> items = new ArrayList<>();

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (subtotalCurrency == null) subtotalCurrency = "INR";
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public void transitionTo(OrderStatus newStatus) {
        if (this.status.isTerminal()) {
            throw new IllegalStateException("Order " + id + " is in terminal state " + status);
        }
        this.status = newStatus;
    }

    public void cancel(String cancelledBy, String reason) {
        if (!this.status.isCancellableByCustomer()) {
            throw new IllegalStateException("Order cannot be cancelled in state " + status);
        }
        this.cancelledBy = cancelledBy;
        this.cancellationReason = reason;
        this.status = OrderStatus.CANCELLING;
    }
}
