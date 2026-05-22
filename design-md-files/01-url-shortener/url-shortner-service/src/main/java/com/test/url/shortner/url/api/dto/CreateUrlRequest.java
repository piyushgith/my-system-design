package com.test.url.shortner.url.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record CreateUrlRequest(
		@NotBlank(message = "longUrl is required") String longUrl,
		String alias,
		@Positive(message = "ttl must be a positive number of seconds") Long ttl) {
}
