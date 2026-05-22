package com.fooddelivery.delivery.repository;

import com.fooddelivery.delivery.domain.DeliveryPartner;
import com.fooddelivery.delivery.domain.DeliveryPartnerStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeliveryPartnerRepository extends JpaRepository<DeliveryPartner, UUID> {
    Optional<DeliveryPartner> findByPhone(String phone);
    List<DeliveryPartner> findByCityIdAndIsOnlineTrueAndStatus(String cityId, DeliveryPartnerStatus status);
}
