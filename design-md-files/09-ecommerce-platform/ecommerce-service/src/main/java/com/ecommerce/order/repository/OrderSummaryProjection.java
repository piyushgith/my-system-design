package com.ecommerce.order.repository;

import com.ecommerce.order.domain.OrderStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public interface OrderSummaryProjection {
    UUID getId();
    OrderStatus getStatus();
    long getTotalAmount();
    String getCurrency();
    long getItemCount();
    LocalDateTime getCreatedAt();
}
