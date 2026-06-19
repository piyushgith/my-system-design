package com.ecommerce.order.repository;

import com.ecommerce.order.domain.Order;
import com.ecommerce.order.domain.OrderItem;
import com.ecommerce.order.domain.OrderStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class OrderRepositoryTest {

    @Autowired OrderRepository orderRepository;

    @Test
    void findSummariesByBuyerIdCountsItemsWithoutLoadingOrders() {
        UUID buyerId = UUID.randomUUID();
        Order order = Order.builder()
                .buyerId(buyerId)
                .status(OrderStatus.PLACED)
                .totalAmount(3000)
                .currency("INR")
                .paymentMethod("COD")
                .shippingAddress("221B Baker Street")
                .idempotencyKey("idem-1")
                .items(List.of(
                        item(UUID.randomUUID(), 1, 1000),
                        item(UUID.randomUUID(), 2, 2000)))
                .build();
        orderRepository.saveAndFlush(order);

        var summaries = orderRepository.findSummariesByBuyerId(buyerId, PageRequest.of(0, 10));

        assertThat(summaries.getTotalElements()).isEqualTo(1);
        OrderSummaryProjection summary = summaries.getContent().getFirst();
        assertThat(summary.getId()).isEqualTo(order.getId());
        assertThat(summary.getStatus()).isEqualTo(OrderStatus.PLACED);
        assertThat(summary.getItemCount()).isEqualTo(2);
    }

    private static OrderItem item(UUID productId, int quantity, long totalPrice) {
        return OrderItem.builder()
                .productId(productId)
                .titleSnapshot("Widget")
                .quantity(quantity)
                .unitPrice(1000)
                .totalPrice(totalPrice)
                .build();
    }
}
