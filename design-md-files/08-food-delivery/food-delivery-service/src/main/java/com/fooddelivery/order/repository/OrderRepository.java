package com.fooddelivery.order.repository;

import com.fooddelivery.order.domain.Order;
import com.fooddelivery.order.domain.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {
    Optional<Order> findByIdempotencyKey(String idempotencyKey);
    Page<Order> findByCustomerIdOrderByCreatedAtDesc(UUID customerId, Pageable pageable);
    Page<Order> findByRestaurantIdAndStatusOrderByCreatedAtDesc(UUID restaurantId, OrderStatus status, Pageable pageable);
    Optional<Order> findByIdAndCustomerId(UUID orderId, UUID customerId);
}
