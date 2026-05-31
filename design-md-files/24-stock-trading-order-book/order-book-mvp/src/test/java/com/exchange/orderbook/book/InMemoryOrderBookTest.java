package com.exchange.orderbook.book;

import com.exchange.orderbook.domain.Order;
import com.exchange.orderbook.domain.OrderSide;
import com.exchange.orderbook.domain.OrderStatus;
import com.exchange.orderbook.domain.Trade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryOrderBookTest {

    private InMemoryOrderBook book;

    @BeforeEach
    void setUp() {
        book = new InMemoryOrderBook();
    }

    private Order order(OrderSide side, String price, int qty) {
        return Order.builder()
            .id(UUID.randomUUID())
            .symbol("AAPL")
            .side(side)
            .price(new BigDecimal(price))
            .quantity(qty)
            .filledQuantity(0)
            .status(OrderStatus.NEW)
            .createdAt(Instant.now())
            .build();
    }

    @Test
    void fullMatch_oneIncomingAgainstOneResting() {
        Order ask = order(OrderSide.SELL, "150.00", 100);
        book.addOrder(ask);

        Order bid = order(OrderSide.BUY, "150.00", 100);
        InMemoryOrderBook.MatchResult result = book.match(bid);

        assertThat(result.trades()).hasSize(1);
        Trade trade = result.trades().get(0);
        assertThat(trade.getPrice()).isEqualByComparingTo("150.00");
        assertThat(trade.getQuantity()).isEqualTo(100);
        assertThat(bid.getStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(ask.getStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(book.getBidDepth(10)).isEmpty();
        assertThat(book.getAskDepth(10)).isEmpty();
    }

    @Test
    void partialFill_incomingLargerThanResting() {
        Order ask = order(OrderSide.SELL, "150.00", 50);
        book.addOrder(ask);

        Order bid = order(OrderSide.BUY, "150.00", 100);
        InMemoryOrderBook.MatchResult result = book.match(bid);

        assertThat(result.trades()).hasSize(1);
        assertThat(result.trades().get(0).getQuantity()).isEqualTo(50);
        assertThat(ask.getStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(bid.getStatus()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(bid.getRemainingQuantity()).isEqualTo(50);
        // remaining bid should rest on the book
        assertThat(book.getBidDepth(10)).containsEntry(new BigDecimal("150.00"), 50);
    }

    @Test
    void noMatch_bidBelowAsk() {
        Order ask = order(OrderSide.SELL, "151.00", 100);
        book.addOrder(ask);

        Order bid = order(OrderSide.BUY, "150.00", 100);
        InMemoryOrderBook.MatchResult result = book.match(bid);

        assertThat(result.trades()).isEmpty();
        assertThat(bid.getStatus()).isEqualTo(OrderStatus.NEW);
        assertThat(book.getBidDepth(10)).containsEntry(new BigDecimal("150.00"), 100);
        assertThat(book.getAskDepth(10)).containsEntry(new BigDecimal("151.00"), 100);
    }

    @Test
    void priceTimePriority_earlierOrderFilledFirst() {
        UUID firstAskId = UUID.randomUUID();
        UUID secondAskId = UUID.randomUUID();

        Order firstAsk = Order.builder().id(firstAskId).symbol("AAPL")
            .side(OrderSide.SELL).price(new BigDecimal("150.00")).quantity(50)
            .filledQuantity(0).status(OrderStatus.NEW).createdAt(Instant.now()).build();
        Order secondAsk = Order.builder().id(secondAskId).symbol("AAPL")
            .side(OrderSide.SELL).price(new BigDecimal("150.00")).quantity(50)
            .filledQuantity(0).status(OrderStatus.NEW).createdAt(Instant.now()).build();

        book.addOrder(firstAsk);
        book.addOrder(secondAsk);

        Order bid = order(OrderSide.BUY, "150.00", 50);
        InMemoryOrderBook.MatchResult result = book.match(bid);

        assertThat(result.trades()).hasSize(1);
        assertThat(result.trades().get(0).getSellOrderId()).isEqualTo(firstAskId);
        assertThat(firstAsk.getStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(secondAsk.getStatus()).isEqualTo(OrderStatus.NEW);
        // second ask still on book
        assertThat(book.getAskDepth(10)).containsEntry(new BigDecimal("150.00"), 50);
    }

    @Test
    void sweepMultiplePriceLevels() {
        Order cheapAsk = order(OrderSide.SELL, "150.00", 40);
        Order expensiveAsk = order(OrderSide.SELL, "151.00", 60);
        book.addOrder(cheapAsk);
        book.addOrder(expensiveAsk);

        Order bid = order(OrderSide.BUY, "151.00", 100);
        InMemoryOrderBook.MatchResult result = book.match(bid);

        assertThat(result.trades()).hasSize(2);
        assertThat(bid.getStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(cheapAsk.getStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(expensiveAsk.getStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(book.getAskDepth(10)).isEmpty();
        assertThat(book.getBidDepth(10)).isEmpty();
    }

    @Test
    void sellAggressiveOrder_fillsAtBidPrice() {
        Order bid = order(OrderSide.BUY, "150.00", 100);
        book.addOrder(bid);

        // Aggressive sell at 149 — should fill at resting bid price of 150
        Order ask = order(OrderSide.SELL, "149.00", 100);
        InMemoryOrderBook.MatchResult result = book.match(ask);

        assertThat(result.trades()).hasSize(1);
        assertThat(result.trades().get(0).getPrice()).isEqualByComparingTo("150.00");
        assertThat(ask.getStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(bid.getStatus()).isEqualTo(OrderStatus.FILLED);
    }

    @Test
    void cancel_removesOrderFromBook() {
        Order ask = order(OrderSide.SELL, "150.00", 100);
        book.addOrder(ask);

        boolean removed = book.removeOrder(ask);

        assertThat(removed).isTrue();
        assertThat(book.getAskDepth(10)).isEmpty();
    }

    @Test
    void cancel_nonExistentOrder_returnsFalse() {
        Order ask = order(OrderSide.SELL, "150.00", 100);
        boolean removed = book.removeOrder(ask);
        assertThat(removed).isFalse();
    }

    @Test
    void partialFillRestingOrder_remainsOnBook() {
        Order ask = order(OrderSide.SELL, "150.00", 100);
        book.addOrder(ask);

        // Buy only 30 out of 100 available
        Order bid = order(OrderSide.BUY, "150.00", 30);
        book.match(bid);

        assertThat(ask.getStatus()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(ask.getRemainingQuantity()).isEqualTo(70);
        assertThat(book.getAskDepth(10)).containsEntry(new BigDecimal("150.00"), 70);
    }
}
