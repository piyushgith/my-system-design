package com.test.chat.shared.error;

import org.springframework.http.HttpStatus;

public class ChatException extends RuntimeException {

	private final String code;
	private final HttpStatus status;

	public ChatException(String code, String message, HttpStatus status) {
		super(message);
		this.code = code;
		this.status = status;
	}

	public String getCode() {
		return code;
	}

	public HttpStatus getStatus() {
		return status;
	}

	public static ChatException badRequest(String code, String message) {
		return new ChatException(code, message, HttpStatus.BAD_REQUEST);
	}

	public static ChatException unauthorized(String message) {
		return new ChatException("UNAUTHORIZED", message, HttpStatus.UNAUTHORIZED);
	}

	public static ChatException forbidden(String code, String message) {
		return new ChatException(code, message, HttpStatus.FORBIDDEN);
	}

	public static ChatException notFound(String code, String message) {
		return new ChatException(code, message, HttpStatus.NOT_FOUND);
	}

	public static ChatException conflict(String code, String message) {
		return new ChatException(code, message, HttpStatus.CONFLICT);
	}
}
