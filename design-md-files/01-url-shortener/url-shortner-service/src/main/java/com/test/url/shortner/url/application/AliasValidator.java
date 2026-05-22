package com.test.url.shortner.url.application;

import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class AliasValidator {

	private static final Set<String> RESERVED_ALIASES = Set.of(
			"api", "health", "metrics", "admin", "auth", "actuator", "swagger", "swagger-ui"
	);

	private static final String ALIAS_PATTERN = "^[a-zA-Z0-9_-]{3,10}$";

	public void validate(String alias) {
		if (alias == null || alias.isBlank()) {
			return;
		}
		if (!alias.matches(ALIAS_PATTERN)) {
			throw new InvalidAliasException("Alias must be 3-10 characters and contain only letters, numbers, hyphens, or underscores");
		}
		if (RESERVED_ALIASES.contains(alias.toLowerCase())) {
			throw new ReservedAliasException("Alias conflicts with a reserved system path");
		}
	}

	public static class InvalidAliasException extends RuntimeException {
		public InvalidAliasException(String message) {
			super(message);
		}
	}

	public static class ReservedAliasException extends RuntimeException {
		public ReservedAliasException(String message) {
			super(message);
		}
	}
}
