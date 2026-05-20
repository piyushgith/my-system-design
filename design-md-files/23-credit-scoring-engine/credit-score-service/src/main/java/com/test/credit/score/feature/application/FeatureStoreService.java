package com.test.credit.score.feature.application;

import com.test.credit.score.feature.domain.FeatureDefinition;
import com.test.credit.score.feature.domain.FeatureDefinitionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Reads/writes per-user features from Redis.
 * Falls back to catalogue defaults when Redis is unavailable (dev/test without Redis).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FeatureStoreService {

    private final StringRedisTemplate redis;
    private final FeatureDefinitionRepository featureDefRepo;

    /**
     * Loads all registered features for a user.
     * Returns Map<featureName, value>. Missing keys fall back to catalogue defaults.
     */
    public Map<String, Double> loadFeatures(String userId) {
        List<FeatureDefinition> defs = featureDefRepo.findAll();
        Map<String, Double> result = new LinkedHashMap<>();

        List<String> keys = defs.stream()
                .filter(d -> !d.getFeatureName().startsWith("meta."))
                .map(d -> d.redisKey(userId))
                .toList();

        List<FeatureDefinition> modelDefs = defs.stream()
                .filter(d -> !d.getFeatureName().startsWith("meta."))
                .toList();

        try {
            List<String> values = redis.opsForValue().multiGet(keys);
            if (values != null) {
                for (int i = 0; i < modelDefs.size(); i++) {
                    FeatureDefinition def = modelDefs.get(i);
                    String raw = values.get(i);
                    result.put(def.getFeatureName(), raw != null ? parseDouble(raw) : def.defaultDouble());
                }
                return result;
            }
        } catch (Exception e) {
            log.warn("Redis unavailable — using default feature values for user {}: {}", userId, e.getMessage());
        }

        // Fallback: all catalogue defaults (thin-file profile)
        modelDefs.forEach(d -> result.put(d.getFeatureName(), d.defaultDouble()));
        return result;
    }

    /**
     * Writes feature values to Redis for a user (manual seeding or pipeline updates).
     */
    public void seedFeatures(String userId, Map<String, String> rawValues) {
        try {
            Map<String, String> keyedValues = new LinkedHashMap<>();
            rawValues.forEach((name, value) -> {
                String pattern = "feature:" + userId + ":" + name;
                keyedValues.put(pattern, value);
            });
            redis.opsForValue().multiSet(keyedValues);
            log.info("Seeded {} features for user {}", keyedValues.size(), userId);
        } catch (Exception e) {
            log.warn("Redis unavailable — feature seeding failed for user {}: {}", userId, e.getMessage());
            throw new RuntimeException("Feature store write failed: " + e.getMessage(), e);
        }
    }

    /**
     * Returns all raw feature strings for a user (admin inspection endpoint).
     */
    public Map<String, String> getRawFeatures(String userId) {
        List<FeatureDefinition> defs = featureDefRepo.findAll();
        Map<String, String> result = new LinkedHashMap<>();

        try {
            for (FeatureDefinition def : defs) {
                String key = def.redisKey(userId);
                String value = redis.opsForValue().get(key);
                if (value != null) result.put(def.getFeatureName(), value);
            }
        } catch (Exception e) {
            log.warn("Redis unavailable for feature inspection of user {}: {}", userId, e.getMessage());
        }

        return result;
    }

    private double parseDouble(String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            // Boolean strings (thin_file flag) map to 1.0/0.0
            if ("true".equalsIgnoreCase(value)) return 1.0;
            if ("false".equalsIgnoreCase(value)) return 0.0;
            return 0.0;
        }
    }
}
