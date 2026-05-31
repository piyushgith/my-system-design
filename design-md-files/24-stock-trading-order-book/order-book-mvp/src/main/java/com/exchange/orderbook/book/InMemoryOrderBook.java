package com.exchange.orderbook.book;

import com.exchange.orderbook.domain.Order;
import com.exchange.orderbook.domain.OrderSide;
import com.exchange.orderbook.domain.Trade;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * Pure domain object — no Spring dependencies.
 * Not thread-safe: callers (MatchingEngine) must synchronize.
 *
 * Bids: TreeMap descending (highest bid first — best price wins).
 * Asks: TreeMap ascending (lowest ask first — best price wins).
 * Within each price level: FIFO queue enforces time priority.
 */
public class InMemoryOrderBook {

    private final TreeMap<BigDecimal, Deque<Order>> bids =
        new TreeMap<>(Comparator.reverseOrder());
    private final TreeMap<BigDecimal, Deque<Order>> asks =
        new TreeMap<>();

    public void addOrder(Order order) {
        bookFor(order.getSide())
            .computeIfAbsent(order.getPrice(), p -> new ArrayDeque<>())
            .addLast(order);
    }

    public void clear() {
        bids.clear();
        asks.clear();
    }

    public boolean removeOrder(Order order) {
        TreeMap<BigDecimal, Deque<Order>> book = bookFor(order.getSide());
        Deque<Order> level = book.get(order.getPrice());
        if (level == null) return false;
        boolean removed = level.removeIf(o -> o.getId().equals(order.getId()));
        if (removed && level.isEmpty()) book.remove(order.getPrice());
        return removed;
    }

    /**
     * Match incoming order against the opposite side of the book.
     * Modifies incoming order and resting orders in-place (filledQuantity, status).
     * Caller is responsible for persisting all modified entities.
     */
    public MatchResult match(Order incoming) {
        List<Trade> trades = new ArrayList<>();
        List<Order> updatedResting = new ArrayList<>();
        TreeMap<BigDecimal, Deque<Order>> counterBook =
            incoming.getSide() == OrderSide.BUY ? asks : bids;

        while (incoming.getRemainingQuantity() > 0 && !counterBook.isEmpty()) {
            Map.Entry<BigDecimal, Deque<Order>> bestEntry = counterBook.firstEntry();
            BigDecimal bestPrice = bestEntry.getKey();

            if (!pricesCross(incoming, bestPrice)) break;

            Deque<Order> levelQueue = bestEntry.getValue();

            while (incoming.getRemainingQuantity() > 0 && !levelQueue.isEmpty()) {
                Order resting = levelQueue.peekFirst();
                int fillQty = Math.min(incoming.getRemainingQuantity(), resting.getRemainingQuantity());

                // Resting (passive) order's price is always used — price-time priority rule
                trades.add(Trade.builder()
                    .buyOrderId(incoming.getSide() == OrderSide.BUY ? incoming.getId() : resting.getId())
                    .sellOrderId(incoming.getSide() == OrderSide.SELL ? incoming.getId() : resting.getId())
                    .symbol(incoming.getSymbol())
                    .price(resting.getPrice())
                    .quantity(fillQty)
                    .executedAt(Instant.now())
                    .build());

                incoming.addFill(fillQty);
                resting.addFill(fillQty);

                if (resting.getRemainingQuantity() == 0) {
                    levelQueue.pollFirst();
                }
                updatedResting.add(resting);
            }

            if (levelQueue.isEmpty()) counterBook.pollFirstEntry();
        }

        // Remaining quantity rests on the book
        if (incoming.getRemainingQuantity() > 0) {
            addOrder(incoming);
        }

        return new MatchResult(trades, updatedResting);
    }

    public Map<BigDecimal, Integer> getBidDepth(int levels) {
        return aggregateDepth(bids, levels);
    }

    public Map<BigDecimal, Integer> getAskDepth(int levels) {
        return aggregateDepth(asks, levels);
    }

    private boolean pricesCross(Order incoming, BigDecimal restingPrice) {
        return incoming.getSide() == OrderSide.BUY
            ? incoming.getPrice().compareTo(restingPrice) >= 0
            : incoming.getPrice().compareTo(restingPrice) <= 0;
    }

    private TreeMap<BigDecimal, Deque<Order>> bookFor(OrderSide side) {
        return side == OrderSide.BUY ? bids : asks;
    }

    private Map<BigDecimal, Integer> aggregateDepth(
            TreeMap<BigDecimal, Deque<Order>> book, int levels) {
        Map<BigDecimal, Integer> depth = new LinkedHashMap<>();
        book.entrySet().stream()
            .limit(levels)
            .forEach(e -> depth.put(
                e.getKey(),
                e.getValue().stream().mapToInt(Order::getRemainingQuantity).sum()
            ));
        return depth;
    }

    public record MatchResult(List<Trade> trades, List<Order> updatedResting) {}
}
