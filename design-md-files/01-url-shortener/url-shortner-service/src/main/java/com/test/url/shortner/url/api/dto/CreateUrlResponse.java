package com.test.url.shortner.url.api.dto;

import java.time.Instant;

public record CreateUrlResponse(
		String shortUrl,
		String shortCode,
		String longUrl,
		Instant createdAt,
		Instant expiresAt) {
}
