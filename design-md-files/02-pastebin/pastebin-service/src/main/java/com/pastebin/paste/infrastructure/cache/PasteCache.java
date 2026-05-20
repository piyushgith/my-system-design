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

    private static final String PASTE_PREFIX = "paste:";
    private static final String NEGATIVE_PREFIX = "paste:missing:";

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
        if (Boolean.TRUE.equals(redisTemplate.hasKey(NEGATIVE_PREFIX + shortKey))) {
            return Optional.empty();
        }
        String cached = redisTemplate.opsForValue().get(PASTE_PREFIX + shortKey);
        if (cached == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(cached, PasteView.class));
        } catch (JsonProcessingException e) {
            redisTemplate.delete(PASTE_PREFIX + shortKey);
            return Optional.empty();
        }
    }

    public void put(String shortKey, PasteView view, Instant expiresAt) {
        try {
            Duration ttl = computeTtl(expiresAt);
            redisTemplate.opsForValue().set(
                    PASTE_PREFIX + shortKey,
                    objectMapper.writeValueAsString(view),
                    ttl
            );
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize paste cache entry", e);
        }
    }

    public void putNegative(String shortKey) {
        redisTemplate.opsForValue().set(NEGATIVE_PREFIX + shortKey, "1", Duration.ofSeconds(negativeTtlSeconds));
    }

    public void evict(String shortKey) {
        redisTemplate.delete(PASTE_PREFIX + shortKey);
        redisTemplate.delete(NEGATIVE_PREFIX + shortKey);
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
