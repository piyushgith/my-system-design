package com.exchange.orderbook.api.dto;

import com.exchange.orderbook.domain.Trade;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TradeResponse(
    UUID id,
    UUID buyOrderId,
    UUID sellOrderId,
    String symbol,
    BigDecimal price,
    int quantity,
    Instant executedAt
) {
    public static TradeResponse from(Trade trade) {
        return new TradeResponse(
            trade.getId(),
            trade.getBuyOrderId(),
            trade.getSellOrderId(),
            trade.getSymbol(),
            trade.getPrice(),
            trade.getQuantity(),
            trade.getExecutedAt()
        );
    }
}
