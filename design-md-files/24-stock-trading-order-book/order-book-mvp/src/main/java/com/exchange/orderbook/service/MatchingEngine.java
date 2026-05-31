package com.exchange.orderbook.service;

import com.exchange.orderbook.book.InMemoryOrderBook;
import com.exchange.orderbook.domain.Order;
import com.exchange.orderbook.domain.OrderStatus;
import com.exchange.orderbook.repository.OrderRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Single-symbol matching engine. Owns the in-memory order book.
 * All public methods synchronized — one order processed at a time.
 *
 * MVP trade-off: synchronized block is simple and correct.
 * V2 evolution: replace with LMAX Disruptor single-writer pattern.
 *
 * Recovery: on startup, loads all open orders from DB sorted by createdAt
 * to restore time priority. Any trade that was in-flight during a crash
 * will be reprocessed if the DB transaction did not commit.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MatchingEngine {

    private final OrderRepository orderRepository;
    private final InMemoryOrderBook orderBook = new InMemoryOrderBook();

    @PostConstruct
    synchronized void recoverFromDatabase() {
        loadOpenOrders("Recovered");
    }

    /**
     * Rebuild the in-memory book from committed DB state. Used as compensation
     * when an order-placement transaction rolls back AFTER the book was mutated —
     * the DB is the source of truth, so reloading from it restores consistency.
     * Must be invoked only after the failed transaction has rolled back.
     */
    public synchronized void rebuild() {
        orderBook.clear();
        loadOpenOrders("Rebuilt");
    }

    private void loadOpenOrders(String action) {
        List<Order> openOrders = orderRepository.findByStatusIn(
            List.of(OrderStatus.NEW, OrderStatus.PARTIALLY_FILLED)
        );
        openOrders.stream()
            // tie-break by id keeps recovery deterministic when createdAt collides;
            // a monotonic sequence column is the V1 fix for true arrival ordering
            .sorted(Comparator.comparing(Order::getCreatedAt).thenComparing(Order::getId))
            .forEach(orderBook::addOrder);
        log.info("{} {} open orders into order book", action, openOrders.size());
    }

    public synchronized InMemoryOrderBook.MatchResult submit(Order order) {
        return orderBook.match(order);
    }

    public synchronized boolean cancel(Order order) {
        return orderBook.removeOrder(order);
    }

    public synchronized Map<BigDecimal, Integer> getBidDepth(int levels) {
        return orderBook.getBidDepth(levels);
    }

    public synchronized Map<BigDecimal, Integer> getAskDepth(int levels) {
        return orderBook.getAskDepth(levels);
    }
}
