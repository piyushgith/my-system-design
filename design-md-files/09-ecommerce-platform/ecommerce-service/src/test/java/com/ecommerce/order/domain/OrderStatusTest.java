package com.ecommerce.order.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderStatusTest {

    @Test
    void allowsForwardLifecycleTransitions() {
        assertThat(OrderStatus.PLACED.canTransitionTo(OrderStatus.CONFIRMED)).isTrue();
        assertThat(OrderStatus.CONFIRMED.canTransitionTo(OrderStatus.SHIPPED)).isTrue();
        assertThat(OrderStatus.SHIPPED.canTransitionTo(OrderStatus.DELIVERED)).isTrue();
    }

    @Test
    void rejectsSkippingAndBackwardTransitions() {
        assertThat(OrderStatus.PLACED.canTransitionTo(OrderStatus.SHIPPED)).isFalse();
        assertThat(OrderStatus.DELIVERED.canTransitionTo(OrderStatus.CANCELLED)).isFalse();
        assertThat(OrderStatus.SHIPPED.canTransitionTo(OrderStatus.CANCELLED)).isFalse();
    }

    @Test
    void customerCancellationOnlyFromEarlyStates() {
        assertThat(OrderStatus.PLACED.isCancellableByCustomer()).isTrue();
        assertThat(OrderStatus.CONFIRMED.isCancellableByCustomer()).isTrue();
        assertThat(OrderStatus.SHIPPED.isCancellableByCustomer()).isFalse();
        assertThat(OrderStatus.DELIVERED.isCancellableByCustomer()).isFalse();
    }
}
