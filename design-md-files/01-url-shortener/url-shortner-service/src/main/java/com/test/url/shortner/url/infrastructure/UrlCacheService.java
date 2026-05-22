package com.test.url.shortner.url.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.test.url.shortner.url.domain.UrlStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class UrlCacheService {

	private static final Logger log = LoggerFactory.getLogger(UrlCacheService.class);
	private static final String KEY_PREFIX = "url:";

	private final StringRedisTemplate redisTemplate;
	private final ObjectMapper objectMapper;
	private final Duration defaultTtl;

	public UrlCacheService(
			StringRedisTemplate redisTemplate,
			ObjectMapper objectMapper,
			@Value("${app.cache.url-ttl-hours:24}") long urlTtlHours) {
		this.redisTemplate = redisTemplate;
		this.objectMapper = objectMapper;
		this.defaultTtl = Duration.ofHours(urlTtlHours);
	}

	public Optional<CachedUrlEntry> get(String shortCode) {
		try {
			String raw = redisTemplate.opsForValue().get(cacheKey(shortCode));
			if (raw == null) {
				return Optional.empty();
			}
			return Optional.of(objectMapper.readValue(raw, CachedUrlEntry.class));
		}
		catch (JsonProcessingException ex) {
			log.warn("Invalid cache entry for shortCode={}, evicting: {}", shortCode, ex.getMessage());
			evict(shortCode);
			return Optional.empty();
		}
		catch (Exception ex) {
			log.warn("Redis cache read failed for shortCode={}: {}", shortCode, ex.getMessage());
			return Optional.empty();
		}
	}

	public void cache(String shortCode, String longUrl, Instant expiresAt, UrlStatus status) {
		try {
			CachedUrlEntry entry = new CachedUrlEntry(longUrl, status, expiresAt);
			String payload = objectMapper.writeValueAsString(entry);
			redisTemplate.opsForValue().set(cacheKey(shortCode), payload, resolveTtl(expiresAt));
		}
		catch (JsonProcessingException ex) {
			log.warn("Failed to serialize cache entry for shortCode={}: {}", shortCode, ex.getMessage());
		}
		catch (Exception ex) {
			log.warn("Redis cache write failed for shortCode={}: {}", shortCode, ex.getMessage());
		}
	}

	public void evict(String shortCode) {
		try {
			redisTemplate.delete(cacheKey(shortCode));
		}
		catch (Exception ex) {
			log.warn("Redis cache eviction failed for shortCode={}: {}", shortCode, ex.getMessage());
		}
	}

	private Duration resolveTtl(Instant expiresAt) {
		if (expiresAt == null) {
			return defaultTtl;
		}
		Duration untilExpiry = Duration.between(Instant.now(), expiresAt);
		if (untilExpiry.isNegative() || untilExpiry.isZero()) {
			return Duration.ofSeconds(1);
		}
		return untilExpiry.compareTo(defaultTtl) < 0 ? untilExpiry : defaultTtl;
	}

	private String cacheKey(String shortCode) {
		return KEY_PREFIX + shortCode;
	}
}
