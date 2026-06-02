package com.fooddelivery.delivery.repository;

import com.fooddelivery.delivery.domain.DeliveryPartner;
import com.fooddelivery.delivery.domain.DeliveryPartnerStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeliveryPartnerRepository extends JpaRepository<DeliveryPartner, UUID> {

    Optional<DeliveryPartner> findByPhone(String phone);

    List<DeliveryPartner> findByCityIdAndIsOnlineTrueAndStatus(String cityId, DeliveryPartnerStatus status);

    @Modifying
    @Query("UPDATE DeliveryPartner p SET p.totalDeliveries = p.totalDeliveries + 1 WHERE p.id = :id")
    void incrementDeliveryCount(@Param("id") UUID id);
}
