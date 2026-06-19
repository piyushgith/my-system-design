package com.ecommerce.cart.service;

import com.ecommerce.common.exception.NotFoundException;
import com.ecommerce.catalog.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CartServiceTest {

    @Mock StringRedisTemplate redis;
    @Mock ProductRepository productRepository;

    @Test
    void updateItemRejectsUnknownProduct() {
        CartService cartService = new CartService(redis, productRepository, 168);
        UUID userId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        when(productRepository.findById(productId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cartService.updateItem(userId, productId, 3))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Product not found");
    }
}
