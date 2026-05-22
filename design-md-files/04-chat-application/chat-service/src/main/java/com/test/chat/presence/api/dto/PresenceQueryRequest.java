package com.test.chat.presence.api.dto;

import com.test.chat.presence.domain.PresenceStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record PresenceQueryRequest(List<UUID> userIds) {
}
