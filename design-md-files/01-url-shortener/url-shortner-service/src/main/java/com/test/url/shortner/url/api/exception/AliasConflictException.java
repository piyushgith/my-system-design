package com.test.url.shortner.url.api.exception;

public class AliasConflictException extends RuntimeException {
	public AliasConflictException(String message) {
		super(message);
	}
}
