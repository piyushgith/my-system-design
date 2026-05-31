package com.exchange.orderbook.service;

import com.exchange.orderbook.api.dto.PlaceOrderRequest;
import com.exchange.orderbook.domain.Order;
import com.exchange.orderbook.domain.OrderSide;
import com.exchange.orderbook.domain.OrderStatus;
import com.exchange.orderbook.domain.Trade;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Service-level tests for the bug fixes. Not @Transactional: writes must commit
 * so the singleton MatchingEngine and DB stay consistent. Each test uses a distinct
 * price band so resting orders from other tests cannot cross and interfere.
 */
@SpringBootTest
class OrderServiceTest {

    @Autowired
    private OrderService orderService;

    private PlaceOrderRequest req(OrderSide side, String price, int qty, String clientOrderId) {
        return new PlaceOrderRequest(side, new BigDecimal(price), qty, clientOrderId);
    }

    @Test
    void cancellingFilledOrder_isRejected_notOverwritten() {
        // resting sell, then a buy that fully consumes it -> sell is FILLED and off the book
        Order sell = orderService.placeOrder(req(OrderSide.SELL, "200.00", 100, null)).order();
        orderService.placeOrder(req(OrderSide.BUY, "200.00", 100, null));

        Order filledSell = orderService.getOrder(sell.getId());
        assertThat(filledSell.getStatus()).isEqualTo(OrderStatus.FILLED);

        // attempting to cancel the filled order must be rejected, never overwritten to CANCELLED
        assertThatThrownBy(() -> orderService.cancelOrder(sell.getId()))
            .isInstanceOf(IllegalStateException.class);
        assertThat(orderService.getOrder(sell.getId()).getStatus()).isEqualTo(OrderStatus.FILLED);
    }

    @Test
    void cancellingRestingOrder_succeeds() {
        Order sell = orderService.placeOrder(req(OrderSide.SELL, "210.00", 50, null)).order();
        Order cancelled = orderService.cancelOrder(sell.getId());
        assertThat(cancelled.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void idempotentReplay_returnsSameOrderAndTrades() {
        String clientId = "idem-" + UUID.randomUUID();
        // rest a sell, then a buy with clientId that fills against it -> produces a trade
        orderService.placeOrder(req(OrderSide.SELL, "220.00", 100, null));
        OrderService.OrderResult first = orderService.placeOrder(req(OrderSide.BUY, "220.00", 100, clientId));
        assertThat(first.trades()).hasSize(1);

        // replay with same clientOrderId: same order, same trades, no double-execution
        OrderService.OrderResult replay = orderService.placeOrder(req(OrderSide.BUY, "220.00", 100, clientId));
        assertThat(replay.order().getId()).isEqualTo(first.order().getId());
        assertThat(replay.trades()).hasSize(1);
        assertThat(replay.trades().get(0).getId()).isEqualTo(first.trades().get(0).getId());
    }

    @Test
    void getOrder_returnsExecutedTrades() {
        orderService.placeOrder(req(OrderSide.SELL, "230.00", 40, null));
        Order buy = orderService.placeOrder(req(OrderSide.BUY, "230.00", 40, null)).order();

        List<Trade> trades = orderService.tradesFor(buy.getId());
        assertThat(trades).hasSize(1);
        assertThat(trades.get(0).getQuantity()).isEqualTo(40);
    }
}
