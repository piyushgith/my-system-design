package com.exchange.orderbook.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "trades", indexes = {
    @Index(name = "idx_trades_symbol_executed_at", columnList = "symbol,executedAt"),
    @Index(name = "idx_trades_buy_order_id", columnList = "buyOrderId"),
    @Index(name = "idx_trades_sell_order_id", columnList = "sellOrderId")
})
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Trade {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID buyOrderId;

    @Column(nullable = false)
    private UUID sellOrderId;

    @Column(nullable = false, length = 10)
    private String symbol;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal price;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false)
    private Instant executedAt;
}
