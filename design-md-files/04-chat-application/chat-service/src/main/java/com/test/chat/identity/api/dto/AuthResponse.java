package com.test.chat.identity.api.dto;

import java.util.UUID;

public record AuthResponse(
		UUID userId,
		String username,
		String displayName,
		String token
) {
}
