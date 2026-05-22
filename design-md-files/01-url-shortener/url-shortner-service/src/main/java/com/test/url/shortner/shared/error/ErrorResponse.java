package com.test.url.shortner.shared.error;

import java.time.Instant;

public record ErrorResponse(
		ErrorDetail error) {

	public ErrorResponse(String code, String message, String field) {
		this(new ErrorDetail(code, message, field, Instant.now()));
	}

	public record ErrorDetail(
			String code,
			String message,
			String field,
			Instant timestamp) {
	}
}
