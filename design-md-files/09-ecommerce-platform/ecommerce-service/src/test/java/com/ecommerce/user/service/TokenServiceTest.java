package com.ecommerce.user.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TokenServiceTest {

    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> values;

    @Test
    void consumeAtomicallyGetsAndDeletesRefreshToken() {
        TokenService tokenService = new TokenService(redis, 1000);
        UUID userId = UUID.randomUUID();
        when(redis.opsForValue()).thenReturn(values);
        when(values.getAndDelete("refresh:token-1")).thenReturn(userId.toString());

        Optional<UUID> result = tokenService.consume("token-1");

        assertThat(result).contains(userId);
        verify(values).getAndDelete("refresh:token-1");
    }
}
