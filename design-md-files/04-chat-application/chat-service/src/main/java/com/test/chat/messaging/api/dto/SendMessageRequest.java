package com.test.chat.messaging.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record SendMessageRequest(
		String idempotencyKey,
		@NotBlank String contentType,
		@NotBlank String content
) {
}
