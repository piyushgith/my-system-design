package com.pastebin.paste.infrastructure.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pastebin.paste.application.PasteView;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Component
public class PasteCache {

    private static final String PREFIX = "paste:";
    private static final String NEGATIVE_SENTINEL = "__MISS__";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final long negativeTtlSeconds;
    private final long maxNeverExpireTtlSeconds;

    public PasteCache(StringRedisTemplate redisTemplate,
                      ObjectMapper objectMapper,
                      PasteCacheProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.negativeTtlSeconds = properties.negativeTtlSeconds();
        this.maxNeverExpireTtlSeconds = properties.maxNeverExpireTtlSeconds();
    }

    public Optional<PasteView> get(String shortKey) {
        String value = redisTemplate.opsForValue().get(PREFIX + shortKey);
        if (value == null || NEGATIVE_SENTINEL.equals(value)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(value, PasteView.class));
        } catch (JsonProcessingException e) {
            redisTemplate.delete(PREFIX + shortKey);
            return Optional.empty();
        }
    }

    public void put(String shortKey, PasteView view, Instant expiresAt) {
        try {
            redisTemplate.opsForValue().set(
                    PREFIX + shortKey,
                    objectMapper.writeValueAsString(view),
                    computeTtl(expiresAt)
            );
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize paste cache entry", e);
        }
    }

    public void putNegative(String shortKey) {
        redisTemplate.opsForValue().set(PREFIX + shortKey, NEGATIVE_SENTINEL,
                Duration.ofSeconds(negativeTtlSeconds));
    }

    public void evict(String shortKey) {
        redisTemplate.delete(PREFIX + shortKey);
    }

    private Duration computeTtl(Instant expiresAt) {
        if (expiresAt == null) {
            return Duration.ofSeconds(maxNeverExpireTtlSeconds);
        }
        Duration remaining = Duration.between(Instant.now(), expiresAt);
        if (remaining.isNegative() || remaining.isZero()) {
            return Duration.ofSeconds(1);
        }
        return remaining.compareTo(Duration.ofSeconds(maxNeverExpireTtlSeconds)) > 0
                ? Duration.ofSeconds(maxNeverExpireTtlSeconds)
                : remaining;
    }
}
