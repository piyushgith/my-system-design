package com.test.credit.score.scoring.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.test.credit.score.config.ScoringProperties;
import com.test.credit.score.scoring.api.ScoreResponse;
import com.test.credit.score.scoring.domain.ProductType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ScoreCacheService {

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final ScoringProperties props;

    private String cacheKey(String userId, ProductType productType) {
        return "score:cache:" + userId + ":" + productType.name();
    }

    public Optional<ScoreResponse> get(String userId, ProductType productType) {
        try {
            String json = redis.opsForValue().get(cacheKey(userId, productType));
            if (json != null) {
                ScoreResponse response = objectMapper.readValue(json, ScoreResponse.class);
                return Optional.of(response);
            }
        } catch (Exception e) {
            log.debug("Score cache miss or Redis unavailable for user {}: {}", userId, e.getMessage());
        }
        return Optional.empty();
    }

    public void put(String userId, ProductType productType, ScoreResponse response) {
        try {
            String json = objectMapper.writeValueAsString(response);
            redis.opsForValue().set(
                    cacheKey(userId, productType),
                    json,
                    Duration.ofMinutes(props.getScoreCacheTtlMinutes()));
        } catch (Exception e) {
            log.warn("Score cache write failed for user {}: {}", userId, e.getMessage());
        }
    }
}
