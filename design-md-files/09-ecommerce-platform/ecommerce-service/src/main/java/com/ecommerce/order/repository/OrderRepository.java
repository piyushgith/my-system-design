package com.ecommerce.order.repository;

import com.ecommerce.order.domain.Order;
import com.ecommerce.order.domain.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    Optional<Order> findByBuyerIdAndIdempotencyKey(UUID buyerId, String idempotencyKey);

    Optional<Order> findByIdAndBuyerId(UUID id, UUID buyerId);

    Page<Order> findByBuyerIdOrderByCreatedAtDesc(UUID buyerId, Pageable pageable);

    @Query(value = """
            select o.id as id,
                   o.status as status,
                   o.totalAmount as totalAmount,
                   o.currency as currency,
                   count(i.id) as itemCount,
                   o.createdAt as createdAt
            from Order o
            left join o.items i
            where o.buyerId = :buyerId
            group by o.id, o.status, o.totalAmount, o.currency, o.createdAt
            order by o.createdAt desc
            """,
            countQuery = "select count(o) from Order o where o.buyerId = :buyerId")
    Page<OrderSummaryProjection> findSummariesByBuyerId(UUID buyerId, Pageable pageable);

    Page<Order> findByStatusOrderByCreatedAtDesc(OrderStatus status, Pageable pageable);

    @Query(value = """
            select o.id as id,
                   o.status as status,
                   o.totalAmount as totalAmount,
                   o.currency as currency,
                   count(i.id) as itemCount,
                   o.createdAt as createdAt
            from Order o
            left join o.items i
            where o.status = :status
            group by o.id, o.status, o.totalAmount, o.currency, o.createdAt
            order by o.createdAt desc
            """,
            countQuery = "select count(o) from Order o where o.status = :status")
    Page<OrderSummaryProjection> findSummariesByStatus(OrderStatus status, Pageable pageable);

    Page<Order> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @Query(value = """
            select o.id as id,
                   o.status as status,
                   o.totalAmount as totalAmount,
                   o.currency as currency,
                   count(i.id) as itemCount,
                   o.createdAt as createdAt
            from Order o
            left join o.items i
            group by o.id, o.status, o.totalAmount, o.currency, o.createdAt
            order by o.createdAt desc
            """,
            countQuery = "select count(o) from Order o")
    Page<OrderSummaryProjection> findAllSummaries(Pageable pageable);
}
