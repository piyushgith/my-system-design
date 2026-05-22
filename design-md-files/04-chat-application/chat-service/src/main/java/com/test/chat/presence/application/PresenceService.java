package com.test.chat.presence.application;

import com.test.chat.presence.domain.PresenceStatus;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class PresenceService {

	private static final Duration ONLINE_TTL = Duration.ofSeconds(90);
	private static final String PRESENCE_PREFIX = "presence:";

	private final StringRedisTemplate redisTemplate;

	public PresenceService(StringRedisTemplate redisTemplate) {
		this.redisTemplate = redisTemplate;
	}

	public void markOnline(UUID userId) {
		redisTemplate.opsForValue().set(key(userId), PresenceStatus.ONLINE.name(), ONLINE_TTL);
	}

	public void markOffline(UUID userId) {
		redisTemplate.opsForValue().set(key(userId), PresenceStatus.OFFLINE.name(), Duration.ofHours(24));
		redisTemplate.opsForValue().set(lastSeenKey(userId), Instant.now().toString(), Duration.ofDays(7));
	}

	public Map<UUID, PresenceView> query(List<UUID> userIds) {
		Map<UUID, PresenceView> result = new HashMap<>();
		for (UUID userId : userIds) {
			String statusValue = redisTemplate.opsForValue().get(key(userId));
			PresenceStatus status = statusValue == null ? PresenceStatus.OFFLINE : PresenceStatus.valueOf(statusValue);
			String lastSeen = redisTemplate.opsForValue().get(lastSeenKey(userId));
			result.put(userId, new PresenceView(status, lastSeen == null ? null : Instant.parse(lastSeen)));
		}
		return result;
	}

	private String key(UUID userId) {
		return PRESENCE_PREFIX + userId;
	}

	private String lastSeenKey(UUID userId) {
		return PRESENCE_PREFIX + userId + ":last_seen";
	}

	public record PresenceView(PresenceStatus status, Instant lastSeen) {
	}
}
