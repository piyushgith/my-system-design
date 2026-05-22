package com.fooddelivery.order.service;

import com.fooddelivery.common.exception.ConflictException;
import com.fooddelivery.common.exception.NotFoundException;
import com.fooddelivery.order.domain.*;
import com.fooddelivery.order.repository.OrderRepository;
import com.fooddelivery.order.service.dto.*;
import com.fooddelivery.restaurant.domain.MenuItem;
import com.fooddelivery.restaurant.repository.MenuItemRepository;
import com.fooddelivery.restaurant.repository.RestaurantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrderService {

    private static final long DELIVERY_FEE_PAISE = 4000L;   // Rs 40
    private static final long PLATFORM_FEE_PAISE = 200L;    // Rs 2

    private final OrderRepository orderRepository;
    private final MenuItemRepository menuItemRepository;
    private final RestaurantRepository restaurantRepository;

    @Transactional
    public PlaceOrderResponse placeOrder(UUID customerId, PlaceOrderRequest request) {
        // Idempotency check
        orderRepository.findByIdempotencyKey(request.idempotencyKey()).ifPresent(existing -> {
            throw new ConflictException("Order already exists: " + existing.getId());
        });

        var restaurant = restaurantRepository.findById(request.restaurantId())
                .orElseThrow(() -> new NotFoundException("Restaurant not found"));

        if (!restaurant.isServicingOrders()) {
            throw new IllegalStateException("Restaurant is not accepting orders");
        }

        // Build order items with price snapshot
        List<OrderItem> items = new ArrayList<>();
        long subtotal = 0;
        for (var lineItem : request.items()) {
            MenuItem menuItem = menuItemRepository.findByIdAndRestaurantId(lineItem.menuItemId(), request.restaurantId())
                    .orElseThrow(() -> new NotFoundException("Menu item not found: " + lineItem.menuItemId()));
            if (!menuItem.isAvailable()) {
                throw new IllegalArgumentException("Item unavailable: " + menuItem.getName());
            }
            long lineTotal = menuItem.effectivePrice() * lineItem.quantity();
            subtotal += lineTotal;
            items.add(OrderItem.builder()
                    .menuItemId(menuItem.getId())
                    .menuItemName(menuItem.getName())
                    .unitPriceAmount(menuItem.effectivePrice())
                    .unitPriceCurrency(menuItem.getPriceCurrency())
                    .quantity(lineItem.quantity())
                    .totalPriceAmount(lineTotal)
                    .customizations(lineItem.customizations())
                    .build());
        }

        if (subtotal < restaurant.getMinimumOrderAmount()) {
            throw new IllegalArgumentException("Order below minimum value");
        }

        long totalAmount = subtotal + DELIVERY_FEE_PAISE + PLATFORM_FEE_PAISE;

        Order order = Order.builder()
                .customerId(customerId)
                .restaurantId(request.restaurantId())
                .deliveryAddressId(request.deliveryAddressId())
                .status(OrderStatus.PAYMENT_PENDING)
                .subtotalAmount(subtotal)
                .subtotalCurrency("INR")
                .deliveryFeeAmount(DELIVERY_FEE_PAISE)
                .discountAmount(0L)
                .platformFeeAmount(PLATFORM_FEE_PAISE)
                .totalAmount(totalAmount)
                .paymentMethod(request.paymentMethod())
                .specialInstructions(request.specialInstructions())
                .idempotencyKey(request.idempotencyKey())
                .cityId(restaurant.getCityId())
                .estimatedDeliveryTime(LocalDateTime.now().plusMinutes(restaurant.getAvgPrepTimeMinutes() + 20))
                .items(items)
                .build();

        order = orderRepository.save(order);

        // MVP: for COD, auto-confirm and notify restaurant
        if ("COD".equals(request.paymentMethod())) {
            order.transitionTo(OrderStatus.PAYMENT_CONFIRMED);
            order.transitionTo(OrderStatus.RESTAURANT_NOTIFIED);
            orderRepository.save(order);
        }

        return PlaceOrderResponse.from(order);
    }

    @Transactional(readOnly = true)
    public OrderDetailResponse getOrder(UUID orderId, UUID customerId) {
        Order order = orderRepository.findByIdAndCustomerId(orderId, customerId)
                .orElseThrow(() -> new NotFoundException("Order not found"));
        return OrderDetailResponse.from(order);
    }

    @Transactional(readOnly = true)
    public Page<OrderSummary> listOrders(UUID customerId, int page, int size) {
        return orderRepository.findByCustomerIdOrderByCreatedAtDesc(customerId, PageRequest.of(page, Math.min(size, 50)))
                .map(OrderSummary::from);
    }

    @Transactional
    public CancelOrderResponse cancelOrder(UUID orderId, UUID customerId, String reason) {
        Order order = orderRepository.findByIdAndCustomerId(orderId, customerId)
                .orElseThrow(() -> new NotFoundException("Order not found"));
        order.cancel("CUSTOMER", reason);
        // MVP: immediately mark CANCELLED (no payment refund flow in MVP)
        order.transitionTo(OrderStatus.CANCELLED);
        orderRepository.save(order);
        return new CancelOrderResponse(order.getId(), OrderStatus.CANCELLED, "Cancellation processed");
    }

    // Restaurant-facing: accept order
    @Transactional
    public void acceptOrder(UUID orderId, int estimatedPrepMinutes) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new NotFoundException("Order not found"));
        order.transitionTo(OrderStatus.RESTAURANT_ACCEPTED);
        order.setEstimatedDeliveryTime(LocalDateTime.now().plusMinutes(estimatedPrepMinutes + 20));
        orderRepository.save(order);
    }

    // Restaurant-facing: reject order
    @Transactional
    public void rejectOrder(UUID orderId, String reason) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new NotFoundException("Order not found"));
        order.transitionTo(OrderStatus.RESTAURANT_REJECTED);
        order.setCancellationReason(reason);
        order.setCancelledBy("RESTAURANT");
        orderRepository.save(order);
    }

    // Restaurant-facing: mark food ready
    @Transactional
    public void markFoodReady(UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new NotFoundException("Order not found"));
        order.transitionTo(OrderStatus.FOOD_READY);
        orderRepository.save(order);
    }

    @Transactional(readOnly = true)
    public Page<OrderDetailResponse> getRestaurantOrders(UUID restaurantId, OrderStatus status, int page, int size) {
        return orderRepository.findByRestaurantIdAndStatusOrderByCreatedAtDesc(
                restaurantId, status, PageRequest.of(page, Math.min(size, 50))
        ).map(OrderDetailResponse::from);
    }
}
