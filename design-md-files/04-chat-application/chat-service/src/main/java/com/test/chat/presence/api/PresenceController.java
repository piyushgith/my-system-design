package com.test.chat.presence.api;

import com.test.chat.presence.api.dto.PresenceQueryRequest;
import com.test.chat.presence.api.dto.PresenceQueryResponse;
import com.test.chat.presence.application.PresenceService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/presence")
public class PresenceController {

	private final PresenceService presenceService;

	public PresenceController(PresenceService presenceService) {
		this.presenceService = presenceService;
	}

	@PostMapping("/query")
	public PresenceQueryResponse query(@Valid @RequestBody PresenceQueryRequest request) {
		Map<UUID, PresenceQueryResponse.PresenceEntry> presence = presenceService.query(request.userIds()).entrySet()
				.stream()
				.collect(Collectors.toMap(
						Map.Entry::getKey,
						e -> new PresenceQueryResponse.PresenceEntry(e.getValue().status(), e.getValue().lastSeen())));
		return new PresenceQueryResponse(presence);
	}
}
