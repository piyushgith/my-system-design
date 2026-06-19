package com.ecommerce.user.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Opaque refresh tokens stored in Redis (key: refresh:{token} -> userId), enabling
 * server-side revocation (logout) that stateless access JWTs alone cannot provide.
 */
@Service
public class TokenService {

    private static final String KEY_PREFIX = "refresh:";

    private final StringRedisTemplate redis;
    private final Duration refreshTtl;

    public TokenService(StringRedisTemplate redis,
                        @Value("${app.jwt.refresh-expiry-ms:1209600000}") long refreshExpiryMs) {
        this.redis = redis;
        this.refreshTtl = Duration.ofMillis(refreshExpiryMs);
    }

    /** Issues a new opaque refresh token bound to the user. */
    public String issue(UUID userId) {
        String token = UUID.randomUUID().toString();
        redis.opsForValue().set(KEY_PREFIX + token, userId.toString(), refreshTtl);
        return token;
    }

    /** Resolves the user id for a refresh token if it exists and has not expired/been revoked. */
    public Optional<UUID> resolve(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String userId = redis.opsForValue().get(KEY_PREFIX + token);
        return Optional.ofNullable(userId).map(UUID::fromString);
    }

    /** Revokes a single refresh token (logout). */
    public void revoke(String token) {
        if (token != null && !token.isBlank()) {
            redis.delete(KEY_PREFIX + token);
        }
    }
}
