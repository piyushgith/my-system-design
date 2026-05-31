package com.exchange.orderbook.api.dto;

import com.exchange.orderbook.domain.Order;
import com.exchange.orderbook.domain.OrderSide;
import com.exchange.orderbook.domain.OrderStatus;
import com.exchange.orderbook.domain.Trade;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderResponse(
    UUID id,
    String symbol,
    OrderSide side,
    OrderStatus status,
    BigDecimal price,
    int quantity,
    int filledQuantity,
    int remainingQuantity,
    String clientOrderId,
    Instant createdAt,
    List<TradeResponse> trades
) {
    public static OrderResponse from(Order order, List<Trade> trades) {
        return new OrderResponse(
            order.getId(),
            order.getSymbol(),
            order.getSide(),
            order.getStatus(),
            order.getPrice(),
            order.getQuantity(),
            order.getFilledQuantity(),
            order.getRemainingQuantity(),
            order.getClientOrderId(),
            order.getCreatedAt(),
            trades.stream().map(TradeResponse::from).toList()
        );
    }
}
