package com.test.notification.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.test.notification.config.AppProperties;
import com.test.notification.domain.enums.Category;
import com.test.notification.domain.enums.Channel;
import com.test.notification.domain.model.UserNotificationPreference;
import com.test.notification.domain.model.UserPreferenceId;
import com.test.notification.domain.repository.UserPreferenceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PreferenceService {

    private final UserPreferenceRepository preferenceRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final AppProperties props;

    private static final String CACHE_PREFIX = "pref:";

    @Transactional(readOnly = true)
    public List<UserNotificationPreference> getPreferences(UUID userId) {
        String cacheKey = CACHE_PREFIX + userId;
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            try {
                return objectMapper.readValue(cached, new TypeReference<>() {});
            } catch (JsonProcessingException e) {
                log.warn("Failed to deserialize cached preferences userId={}", userId);
            }
        }
        List<UserNotificationPreference> prefs = preferenceRepository.findByUserId(userId);
        cachePreferences(cacheKey, prefs);
        return prefs;
    }

    @Transactional(readOnly = true)
    public boolean isOptedIn(UUID userId, Channel channel, Category category) {
        return getPreferences(userId).stream()
                .filter(p -> p.getChannel() == channel && p.getCategory() == category)
                .findFirst()
                .map(p -> p.isOptedIn() && !p.isHardUnsubscribed())
                .orElse(true); // default: opted-in if no preference record exists
    }

    @Transactional
    public List<UserNotificationPreference> upsertPreferences(
            UUID userId, List<UserNotificationPreference> updates) {

        for (UserNotificationPreference update : updates) {
            update.setUserId(userId);
            update.setUpdatedAt(Instant.now());
            preferenceRepository.save(update);
        }
        evictCache(userId);
        return preferenceRepository.findByUserId(userId);
    }

    @Transactional
    public Optional<UserNotificationPreference> processUnsubscribeToken(String token) {
        return preferenceRepository.findByUnsubscribeToken(token).map(pref -> {
            pref.setOptedIn(false);
            pref.setUpdatedAt(Instant.now());
            UserNotificationPreference saved = preferenceRepository.save(pref);
            evictCache(pref.getUserId());
            return saved;
        });
    }

    @Transactional
    public void hardUnsubscribe(UUID userId, Channel channel, Category category) {
        UserPreferenceId id = new UserPreferenceId(userId, channel, category);
        preferenceRepository.findById(id).ifPresent(pref -> {
            pref.setOptedIn(false);
            pref.setHardUnsubscribed(true);
            pref.setHardUnsubscribedAt(Instant.now());
            pref.setUpdatedAt(Instant.now());
            preferenceRepository.save(pref);
            evictCache(userId);
        });
    }

    private void cachePreferences(String key, List<UserNotificationPreference> prefs) {
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(prefs),
                    Duration.ofMinutes(props.getCache().getPreferenceTtlMinutes()));
        } catch (JsonProcessingException e) {
            log.warn("Failed to cache preferences key={}", key);
        }
    }

    private void evictCache(UUID userId) {
        redisTemplate.delete(CACHE_PREFIX + userId);
    }
}
