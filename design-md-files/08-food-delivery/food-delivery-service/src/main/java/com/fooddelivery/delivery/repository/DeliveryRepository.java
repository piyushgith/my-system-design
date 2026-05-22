package com.fooddelivery.delivery.repository;

import com.fooddelivery.delivery.domain.Delivery;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DeliveryRepository extends JpaRepository<Delivery, UUID> {
    Optional<Delivery> findByOrderId(UUID orderId);
    Optional<Delivery> findByPartnerId(UUID partnerId);
}
