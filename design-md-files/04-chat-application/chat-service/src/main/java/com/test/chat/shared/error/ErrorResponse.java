package com.test.chat.shared.error;

import java.time.Instant;
import java.util.List;

public record ErrorResponse(
		String code,
		String message,
		String requestId,
		Instant timestamp,
		List<FieldError> errors
) {
	public record FieldError(String field, String message) {
	}
}
