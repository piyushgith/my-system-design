package com.ecommerce.order.service;

import com.ecommerce.cart.service.CartService;
import com.ecommerce.catalog.domain.Product;
import com.ecommerce.catalog.repository.ProductRepository;
import com.ecommerce.common.exception.NotFoundException;
import com.ecommerce.common.outbox.OutboxService;
import com.ecommerce.common.security.AuditContext;
import com.ecommerce.order.domain.Order;
import com.ecommerce.order.domain.OrderItem;
import com.ecommerce.order.domain.OrderStatus;
import com.ecommerce.order.event.OrderEvents;
import com.ecommerce.order.payment.CodPaymentProcessor;
import com.ecommerce.order.payment.PaymentProcessor;
import com.ecommerce.order.payment.PaymentProcessorRegistry;
import com.ecommerce.order.repository.OrderRepository;
import com.ecommerce.order.service.dto.OrderResponse;
import com.ecommerce.order.service.dto.OrderSummary;
import com.ecommerce.order.service.dto.CheckoutRequest;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private static final int MAX_PAGE_SIZE = 50;
    private static final String ORDER_NOT_FOUND = "Order not found";

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final CartService cartService;
    private final PaymentProcessorRegistry paymentProcessorRegistry;
    private final OutboxService outboxService;

    /**
     * Checkout the buyer's Redis cart into a COD order.
     * Idempotent on idempotencyKey. Decrements product stock under optimistic
     * lock (@Version) so concurrent checkouts cannot oversell.
     */
    @Transactional
    public OrderResponse checkout(UUID buyerId, CheckoutRequest request) {
        Order existing = orderRepository
                .findByBuyerIdAndIdempotencyKey(buyerId, request.idempotencyKey()).orElse(null);
        if (existing != null) {
            return OrderResponse.from(existing);   // replay — return the original order
        }

        Map<UUID, Integer> cart = cartService.rawItems(buyerId);
        if (cart.isEmpty()) {
            throw new IllegalStateException("Cart is empty");
        }

        List<Product> products = productRepository.findAllById(cart.keySet());
        Map<UUID, Product> byId = new LinkedHashMap<>();
        products.forEach(p -> byId.put(p.getId(), p));

        List<OrderItem> items = new ArrayList<>();
        long total = 0;
        String currency = "INR";
        for (Map.Entry<UUID, Integer> entry : cart.entrySet()) {
            Product product = byId.get(entry.getKey());
            if (product == null || !product.isActive()) {
                throw new IllegalStateException("Product no longer available: " + entry.getKey());
            }
            int qty = entry.getValue();
            if (!product.hasStock(qty)) {
                throw new IllegalStateException("Insufficient stock for: " + product.getTitle());
            }
            product.decrementStock(qty);
            productRepository.save(product);

            long lineTotal = product.getPriceAmount() * qty;
            total += lineTotal;
            currency = product.getCurrency();
            items.add(OrderItem.builder()
                    .productId(product.getId())
                    .titleSnapshot(product.getTitle())
                    .quantity(qty)
                    .unitPrice(product.getPriceAmount())
                    .totalPrice(lineTotal)
                    .build());
        }

        // MVP supports COD only; routing through the registry keeps the seam for future gateways.
        PaymentProcessor payment = paymentProcessorRegistry.forMethod(CodPaymentProcessor.METHOD);
        Order order = Order.builder()
                .buyerId(buyerId)
                .status(OrderStatus.PLACED)
                .totalAmount(total)
                .currency(currency)
                .paymentMethod(payment.method())
                .shippingAddress(request.shippingAddress())
                .idempotencyKey(request.idempotencyKey())
                .items(items)
                .build();
        payment.authorize(order);
        order = orderRepository.save(order);

        // Record the domain event in the same transaction as the order write (outbox pattern).
        outboxService.record(OrderEvents.AGGREGATE_TYPE, order.getId(), OrderEvents.ORDER_PLACED,
                new OrderEvents.OrderPlaced(order.getId(), order.getBuyerId(),
                        order.getTotalAmount(), order.getCurrency()));

        // Clear the Redis cart only once the DB transaction has durably committed,
        // so a commit failure cannot leave the buyer without both cart and order.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cartService.clear(buyerId);
                }
            });
        } else {
            cartService.clear(buyerId);
        }
        return OrderResponse.from(order);
    }

    @Transactional(readOnly = true)
    public Page<OrderSummary> listOrders(UUID buyerId, int page, int size) {
        return orderRepository.findByBuyerIdOrderByCreatedAtDesc(buyerId, pageable(page, size))
                .map(OrderSummary::from);
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrder(UUID orderId, UUID buyerId) {
        Order order = orderRepository.findByIdAndBuyerId(orderId, buyerId)
                .orElseThrow(() -> new NotFoundException(ORDER_NOT_FOUND));
        return OrderResponse.from(order);
    }

    @Transactional
    public OrderResponse cancelOrder(UUID orderId, UUID buyerId) {
        Order order = orderRepository.findByIdAndBuyerId(orderId, buyerId)
                .orElseThrow(() -> new NotFoundException(ORDER_NOT_FOUND));
        order.cancelByCustomer();
        restock(order);
        order = orderRepository.save(order);
        outboxService.record(OrderEvents.AGGREGATE_TYPE, order.getId(), OrderEvents.ORDER_CANCELLED,
                new OrderEvents.OrderCancelled(order.getId(), order.getBuyerId()));
        return OrderResponse.from(order);
    }

    // ---------- Admin ----------

    @Transactional(readOnly = true)
    public Page<OrderSummary> listAllOrders(OrderStatus status, int page, int size) {
        Pageable pageable = pageable(page, size);
        Page<Order> orders = (status == null)
                ? orderRepository.findAllByOrderByCreatedAtDesc(pageable)
                : orderRepository.findByStatusOrderByCreatedAtDesc(status, pageable);
        return orders.map(OrderSummary::from);
    }

    @Transactional
    public OrderResponse updateStatus(UUID orderId, String newStatus) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new NotFoundException(ORDER_NOT_FOUND));
        OrderStatus from = order.getStatus();
        OrderStatus target = parseStatus(newStatus);
        order.transitionTo(target);
        if (target == OrderStatus.CANCELLED) {
            restock(order);
            outboxService.record(OrderEvents.AGGREGATE_TYPE, order.getId(), OrderEvents.ORDER_CANCELLED,
                    new OrderEvents.OrderCancelled(order.getId(), order.getBuyerId()));
        }
        log.info("AUDIT actor={} action=UPDATE_ORDER_STATUS orderId={} from={} to={}",
                AuditContext.currentActor(), orderId, from, target);
        return OrderResponse.from(orderRepository.save(order));
    }

    /** Returns reserved units to inventory when an order is cancelled. */
    private void restock(Order order) {
        for (OrderItem item : order.getItems()) {
            productRepository.findById(item.getProductId()).ifPresentOrElse(product -> {
                product.setStockQuantity(product.getStockQuantity() + item.getQuantity());
                productRepository.save(product);
            }, () -> log.warn(
                    "Restock skipped for order {}: product {} no longer exists (qty {} not returned)",
                    order.getId(), item.getProductId(), item.getQuantity()));
        }
    }

    private static OrderStatus parseStatus(String status) {
        try {
            return OrderStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid order status: " + status);
        }
    }

    private static Pageable pageable(int page, int size) {
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
    }
}
