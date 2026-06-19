package com.ecommerce.order.service;

import com.ecommerce.cart.service.CartService;
import com.ecommerce.catalog.domain.Product;
import com.ecommerce.catalog.domain.ProductStatus;
import com.ecommerce.catalog.repository.ProductRepository;
import com.ecommerce.common.outbox.OutboxService;
import com.ecommerce.order.domain.Order;
import com.ecommerce.order.domain.OrderStatus;
import com.ecommerce.order.payment.CodPaymentProcessor;
import com.ecommerce.order.payment.PaymentProcessorRegistry;
import com.ecommerce.order.repository.OrderRepository;
import com.ecommerce.order.service.dto.CheckoutRequest;
import com.ecommerce.order.service.dto.OrderResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock OrderRepository orderRepository;
    @Mock ProductRepository productRepository;
    @Mock CartService cartService;
    @Mock OutboxService outboxService;

    OrderService orderService;

    private final UUID buyerId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        PaymentProcessorRegistry registry =
                new PaymentProcessorRegistry(List.of(new CodPaymentProcessor()));
        orderService = new OrderService(orderRepository, productRepository, cartService,
                registry, outboxService);
    }

    private Product product(int stock) {
        return Product.builder()
                .id(productId).categoryId(UUID.randomUUID()).title("Widget")
                .priceAmount(1000).currency("INR").stockQuantity(stock)
                .status(ProductStatus.ACTIVE).build();
    }

    private CheckoutRequest request() {
        return new CheckoutRequest("221B Baker Street", "idem-key-1");
    }

    @Test
    void checkoutDecrementsStockAndRecordsOutboxEvent() {
        when(orderRepository.findByBuyerIdAndIdempotencyKey(buyerId, "idem-key-1"))
                .thenReturn(Optional.empty());
        when(cartService.rawItems(buyerId)).thenReturn(Map.of(productId, 2));
        Product product = product(5);
        when(productRepository.findAllById(any())).thenReturn(List.of(product));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        OrderResponse response = orderService.checkout(buyerId, request());

        assertThat(response.status()).isEqualTo(OrderStatus.PLACED.name());
        assertThat(response.totalAmount()).isEqualTo(2000);
        assertThat(product.getStockQuantity()).isEqualTo(3);
        verify(outboxService).record(eq("ORDER"), any(), eq("OrderPlaced"), any());
        verify(cartService).clear(buyerId);
    }

    @Test
    void checkoutIsIdempotentOnReplay() {
        Order existing = Order.builder()
                .id(UUID.randomUUID()).buyerId(buyerId).status(OrderStatus.PLACED)
                .totalAmount(2000).currency("INR").paymentMethod("COD")
                .shippingAddress("x").idempotencyKey("idem-key-1").items(List.of()).build();
        when(orderRepository.findByBuyerIdAndIdempotencyKey(buyerId, "idem-key-1"))
                .thenReturn(Optional.of(existing));

        OrderResponse response = orderService.checkout(buyerId, request());

        assertThat(response.id()).isEqualTo(existing.getId());
        verify(orderRepository, never()).save(any());
        verify(cartService, never()).clear(any());
        verifyNoInteractions(outboxService);
    }

    @Test
    void checkoutRejectsInsufficientStock() {
        when(orderRepository.findByBuyerIdAndIdempotencyKey(buyerId, "idem-key-1"))
                .thenReturn(Optional.empty());
        when(cartService.rawItems(buyerId)).thenReturn(Map.of(productId, 10));
        when(productRepository.findAllById(any())).thenReturn(List.of(product(1)));

        assertThatThrownBy(() -> orderService.checkout(buyerId, request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Insufficient stock");
        verify(orderRepository, never()).save(any());
    }

    @Test
    void checkoutRejectsEmptyCart() {
        when(orderRepository.findByBuyerIdAndIdempotencyKey(buyerId, "idem-key-1"))
                .thenReturn(Optional.empty());
        when(cartService.rawItems(buyerId)).thenReturn(Map.of());

        assertThatThrownBy(() -> orderService.checkout(buyerId, request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cart is empty");
    }
}
