package com.exchange.orderbook.service;

import com.exchange.orderbook.api.dto.PlaceOrderRequest;
import com.exchange.orderbook.book.InMemoryOrderBook;
import com.exchange.orderbook.domain.Order;
import com.exchange.orderbook.domain.OrderStatus;
import com.exchange.orderbook.domain.Trade;
import com.exchange.orderbook.exception.OrderNotFoundException;
import com.exchange.orderbook.repository.OrderRepository;
import com.exchange.orderbook.repository.TradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    public static final String SYMBOL = "AAPL";

    private final OrderRepository orderRepository;
    private final TradeRepository tradeRepository;
    private final MatchingEngine matchingEngine;

    @Transactional
    public OrderResult placeOrder(PlaceOrderRequest request) {
        // Idempotency fast-path: return existing order (with its trades) if already seen.
        if (request.clientOrderId() != null) {
            Optional<Order> existing = orderRepository.findByClientOrderId(request.clientOrderId());
            if (existing.isPresent()) {
                log.debug("Duplicate clientOrderId {}, returning existing order", request.clientOrderId());
                return new OrderResult(existing.get(), tradesFor(existing.get().getId()));
            }
        }

        Order order = Order.builder()
            .symbol(SYMBOL)
            .side(request.side())
            .price(request.price())
            .quantity(request.quantity())
            .filledQuantity(0)
            .status(OrderStatus.NEW)
            .clientOrderId(request.clientOrderId())
            .createdAt(Instant.now())
            .build();

        // saveAndFlush BEFORE matching: surfaces the clientOrderId unique-constraint
        // violation synchronously so a concurrent duplicate is rejected before the
        // in-memory book is ever mutated. Also assigns the UUID needed for Trade records.
        try {
            order = orderRepository.saveAndFlush(order);
        } catch (DataIntegrityViolationException e) {
            // Lost an idempotency race — the winner already inserted. Return its result.
            Order winner = orderRepository.findByClientOrderId(request.clientOrderId())
                .orElseThrow(() -> e);
            log.debug("Idempotency race on clientOrderId {}, returning winner", request.clientOrderId());
            return new OrderResult(winner, tradesFor(winner.getId()));
        }

        // The book is mutated here, OUTSIDE the DB commit. If this transaction later
        // rolls back, the book and DB diverge — so register a compensation that rebuilds
        // the book from committed DB state once rollback completes.
        registerBookRebuildOnRollback();

        InMemoryOrderBook.MatchResult result = matchingEngine.submit(order);

        List<Trade> savedTrades = tradeRepository.saveAll(result.trades());
        orderRepository.saveAll(result.updatedResting());
        order = orderRepository.save(order);

        log.info("Order {} placed: side={} price={} qty={} status={} trades={}",
            order.getId(), order.getSide(), order.getPrice(),
            order.getQuantity(), order.getStatus(), savedTrades.size());

        return new OrderResult(order, savedTrades);
    }

    @Transactional
    public Order cancelOrder(UUID orderId) {
        Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> new OrderNotFoundException(orderId));

        if (isTerminal(order.getStatus())) {
            throw new IllegalStateException(
                "Order " + orderId + " cannot be cancelled, status=" + order.getStatus());
        }

        // Removal under the engine lock is authoritative: success means the order was
        // genuinely resting and no further fills can occur. Failure means it left the
        // book (fully filled at placement, or already cancelled) — never overwrite that.
        boolean removed = matchingEngine.cancel(order);
        if (!removed) {
            Order fresh = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
            throw new IllegalStateException(
                "Order " + orderId + " is no longer open, status=" + fresh.getStatus());
        }

        order.setStatus(OrderStatus.CANCELLED);
        return orderRepository.save(order);
    }

    @Transactional(readOnly = true)
    public Order getOrder(UUID orderId) {
        return orderRepository.findById(orderId)
            .orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    @Transactional(readOnly = true)
    public List<Trade> tradesFor(UUID orderId) {
        return tradeRepository.findByBuyOrderIdOrSellOrderIdOrderByExecutedAtAsc(orderId, orderId);
    }

    @Transactional(readOnly = true)
    public Page<Order> listOrders(OrderStatus status, Pageable pageable) {
        return status != null
            ? orderRepository.findBySymbolAndStatus(SYMBOL, status, pageable)
            : orderRepository.findBySymbol(SYMBOL, pageable);
    }

    @Transactional(readOnly = true)
    public Page<Trade> getRecentTrades(Pageable pageable) {
        return tradeRepository.findBySymbolOrderByExecutedAtDesc(SYMBOL, pageable);
    }

    private boolean isTerminal(OrderStatus status) {
        return status == OrderStatus.FILLED || status == OrderStatus.CANCELLED;
    }

    private void registerBookRebuildOnRollback() {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    log.warn("Placement transaction rolled back — rebuilding order book from DB");
                    matchingEngine.rebuild();
                }
            }
        });
    }

    public record OrderResult(Order order, List<Trade> trades) {}
}
