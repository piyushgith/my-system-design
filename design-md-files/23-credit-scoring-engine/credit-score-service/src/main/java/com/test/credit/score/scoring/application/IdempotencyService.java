package com.test.credit.score.scoring.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.test.credit.score.config.ScoringProperties;
import com.test.credit.score.scoring.api.ScoreResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * Prevents duplicate score computations for the same request_id.
 * Redis key: idempotency:{request_id} → serialized ScoreResponse JSON.
 * TTL matches app.scoring.idempotency-ttl-hours (default 24h).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IdempotencyService {

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final ScoringProperties props;

    private String key(String requestId) {
        return "idempotency:" + requestId;
    }

    public Optional<ScoreResponse> get(String requestId) {
        try {
            String json = redis.opsForValue().get(key(requestId));
            if (json != null) {
                return Optional.of(objectMapper.readValue(json, ScoreResponse.class));
            }
        } catch (Exception e) {
            log.debug("Idempotency lookup failed for {}: {}", requestId, e.getMessage());
        }
        return Optional.empty();
    }

    public void put(String requestId, ScoreResponse response) {
        try {
            String json = objectMapper.writeValueAsString(response);
            redis.opsForValue().set(key(requestId), json, Duration.ofHours(props.getIdempotencyTtlHours()));
        } catch (Exception e) {
            log.warn("Idempotency write failed for {}: {}", requestId, e.getMessage());
        }
    }
}
