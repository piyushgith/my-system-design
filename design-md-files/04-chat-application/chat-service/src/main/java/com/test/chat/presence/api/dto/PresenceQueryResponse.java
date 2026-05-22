package com.test.chat.presence.api.dto;

import com.test.chat.presence.domain.PresenceStatus;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record PresenceQueryResponse(Map<UUID, PresenceEntry> presence) {

	public record PresenceEntry(PresenceStatus status, Instant lastSeen) {
	}
}
