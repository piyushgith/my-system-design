package com.fooddelivery.delivery.service.dto;

import com.fooddelivery.delivery.domain.Delivery;
import com.fooddelivery.delivery.domain.DeliveryStatus;

import java.util.UUID;

public record DeliveryResponse(
        UUID id,
        UUID orderId,
        UUID partnerId,
        DeliveryStatus status,
        long deliveryFeeAmount,
        long partnerEarningAmount
) {
    public static DeliveryResponse from(Delivery d) {
        return new DeliveryResponse(
                d.getId(), d.getOrderId(), d.getPartnerId(),
                d.getStatus(), d.getDeliveryFeeAmount(), d.getPartnerEarningAmount()
        );
    }
}
