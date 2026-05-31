package com.exchange.orderbook.repository;

import com.exchange.orderbook.domain.Order;
import com.exchange.orderbook.domain.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    Optional<Order> findByClientOrderId(String clientOrderId);

    List<Order> findByStatusIn(List<OrderStatus> statuses);

    Page<Order> findBySymbol(String symbol, Pageable pageable);

    Page<Order> findBySymbolAndStatus(String symbol, OrderStatus status, Pageable pageable);
}
