package com.exchange.orderbook.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "orders", indexes = {
    @Index(name = "idx_orders_status", columnList = "status"),
    @Index(name = "idx_orders_symbol_status", columnList = "symbol,status"),
    @Index(name = "idx_orders_client_order_id", columnList = "clientOrderId", unique = true)
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 10)
    private String symbol;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 4)
    private OrderSide side;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal price;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false)
    private int filledQuantity;

    @Column(unique = true)
    private String clientOrderId;

    @Column(nullable = false)
    private Instant createdAt;

    @Version
    private long version;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
        if (status == null) status = OrderStatus.NEW;
    }

    public int getRemainingQuantity() {
        return quantity - filledQuantity;
    }

    public void addFill(int qty) {
        this.filledQuantity += qty;
        this.status = (this.filledQuantity >= this.quantity)
            ? OrderStatus.FILLED
            : OrderStatus.PARTIALLY_FILLED;
    }
}
