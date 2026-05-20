package com.pastebin.paste.infrastructure.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

@Component
public class IdempotencyStore {

    private static final String PREFIX = "idempotency:";
    private static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public IdempotencyStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public <T> Optional<T> get(String key, Class<T> type) {
        String value = redisTemplate.opsForValue().get(PREFIX + key);
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(value, type));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public boolean tryAcquire(String key) {
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(PREFIX + key + ":lock", "1", TTL);
        return Boolean.TRUE.equals(acquired);
    }

    public <T> void store(String key, T response) {
        try {
            redisTemplate.opsForValue().set(PREFIX + key, objectMapper.writeValueAsString(response), TTL);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to store idempotency response", e);
        }
    }
}
