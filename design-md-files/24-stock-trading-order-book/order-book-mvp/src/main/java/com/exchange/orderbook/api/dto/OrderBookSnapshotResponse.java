package com.exchange.orderbook.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record OrderBookSnapshotResponse(
    String symbol,
    List<PriceLevel> bids,
    List<PriceLevel> asks,
    Instant timestamp
) {
    public record PriceLevel(BigDecimal price, int totalQuantity) {}

    public static OrderBookSnapshotResponse of(
            String symbol,
            Map<BigDecimal, Integer> bids,
            Map<BigDecimal, Integer> asks) {
        return new OrderBookSnapshotResponse(
            symbol,
            bids.entrySet().stream()
                .map(e -> new PriceLevel(e.getKey(), e.getValue()))
                .toList(),
            asks.entrySet().stream()
                .map(e -> new PriceLevel(e.getKey(), e.getValue()))
                .toList(),
            Instant.now()
        );
    }
}
