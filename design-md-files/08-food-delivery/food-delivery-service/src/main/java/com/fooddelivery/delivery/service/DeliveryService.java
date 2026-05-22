package com.fooddelivery.delivery.service;

import com.fooddelivery.common.exception.NotFoundException;
import com.fooddelivery.delivery.domain.*;
import com.fooddelivery.delivery.repository.DeliveryPartnerRepository;
import com.fooddelivery.delivery.repository.DeliveryRepository;
import com.fooddelivery.delivery.service.dto.*;
import com.fooddelivery.order.domain.Order;
import com.fooddelivery.order.domain.OrderStatus;
import com.fooddelivery.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DeliveryService {

    private static final String LOCATION_KEY_PREFIX = "driver:location:";
    private static final Duration LOCATION_TTL = Duration.ofSeconds(30);

    private final DeliveryRepository deliveryRepository;
    private final DeliveryPartnerRepository partnerRepository;
    private final OrderRepository orderRepository;
    private final StringRedisTemplate redisTemplate;

    /** Partner reports their current GPS location. Stored in Redis with 30s TTL. */
    @Transactional
    public void updateLocation(UUID partnerId, BigDecimal lat, BigDecimal lng) {
        DeliveryPartner partner = partnerRepository.findById(partnerId)
                .orElseThrow(() -> new NotFoundException("Delivery partner not found"));
        if (!partner.isOnline()) {
            throw new IllegalStateException("Partner is offline — location update rejected");
        }
        String key = LOCATION_KEY_PREFIX + partnerId;
        String value = lat + "," + lng;
        redisTemplate.opsForValue().set(key, value, LOCATION_TTL);
    }

    /** MVP: Manual assignment — ops team calls this to assign a partner to an order. */
    @Transactional
    public DeliveryResponse assignPartner(UUID orderId, UUID partnerId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new NotFoundException("Order not found"));
        DeliveryPartner partner = partnerRepository.findById(partnerId)
                .orElseThrow(() -> new NotFoundException("Delivery partner not found"));

        if (partner.getStatus() != DeliveryPartnerStatus.ACTIVE || !partner.isOnline()) {
            throw new IllegalStateException("Partner not available");
        }

        Delivery delivery = Delivery.builder()
                .orderId(orderId)
                .partnerId(partnerId)
                .restaurantLat(BigDecimal.ZERO)  // populated from restaurant record in real flow
                .restaurantLng(BigDecimal.ZERO)
                .customerLat(BigDecimal.ZERO)
                .customerLng(BigDecimal.ZERO)
                .deliveryFeeAmount(4000L)
                .partnerEarningAmount(3000L)
                .build();

        deliveryRepository.save(delivery);

        order.setDeliveryPartnerId(partnerId);
        order.transitionTo(OrderStatus.DELIVERY_PARTNER_ASSIGNED);
        orderRepository.save(order);

        return DeliveryResponse.from(delivery);
    }

    /** Partner marks food picked up from restaurant. */
    @Transactional
    public void markPickedUp(UUID orderId, UUID partnerId) {
        Delivery delivery = findDelivery(orderId, partnerId);
        delivery.setStatus(DeliveryStatus.PICKED_UP);
        deliveryRepository.save(delivery);

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new NotFoundException("Order not found"));
        order.transitionTo(OrderStatus.PICKED_UP);
        orderRepository.save(order);
    }

    /** Partner marks order delivered. */
    @Transactional
    public void markDelivered(UUID orderId, UUID partnerId) {
        Delivery delivery = findDelivery(orderId, partnerId);
        delivery.setStatus(DeliveryStatus.DELIVERED);
        deliveryRepository.save(delivery);

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new NotFoundException("Order not found"));
        order.transitionTo(OrderStatus.DELIVERED);
        orderRepository.save(order);

        // Increment partner delivery count
        DeliveryPartner partner = partnerRepository.findById(partnerId)
                .orElseThrow(() -> new NotFoundException("Partner not found"));
        partner.setTotalDeliveries(partner.getTotalDeliveries() + 1);
        partnerRepository.save(partner);
    }

    @Transactional
    public void setOnlineStatus(UUID partnerId, boolean online) {
        DeliveryPartner partner = partnerRepository.findById(partnerId)
                .orElseThrow(() -> new NotFoundException("Delivery partner not found"));
        partner.setOnline(online);
        partnerRepository.save(partner);
        if (!online) {
            redisTemplate.delete(LOCATION_KEY_PREFIX + partnerId);
        }
    }

    public PartnerLocationResponse getDriverLocation(UUID orderId) {
        Delivery delivery = deliveryRepository.findByOrderId(orderId)
                .orElseThrow(() -> new NotFoundException("Delivery not found for order"));
        String key = LOCATION_KEY_PREFIX + delivery.getPartnerId();
        String value = redisTemplate.opsForValue().get(key);
        if (value == null) return new PartnerLocationResponse(null, null, false);
        String[] parts = value.split(",");
        return new PartnerLocationResponse(
                new BigDecimal(parts[0]), new BigDecimal(parts[1]), true
        );
    }

    private Delivery findDelivery(UUID orderId, UUID partnerId) {
        Delivery delivery = deliveryRepository.findByOrderId(orderId)
                .orElseThrow(() -> new NotFoundException("Delivery not found"));
        if (!delivery.getPartnerId().equals(partnerId)) {
            throw new IllegalStateException("Partner not assigned to this order");
        }
        return delivery;
    }
}
