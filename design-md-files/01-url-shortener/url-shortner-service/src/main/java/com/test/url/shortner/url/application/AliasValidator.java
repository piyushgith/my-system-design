package com.test.url.shortner.url.application;

import com.test.url.shortner.url.api.ReservedPaths;
import com.test.url.shortner.url.api.exception.InvalidAliasException;
import com.test.url.shortner.url.api.exception.ReservedAliasException;
import org.springframework.stereotype.Component;

@Component
public class AliasValidator {

	private static final String ALIAS_PATTERN = "^[a-zA-Z0-9_-]{3,10}$";

	public void validate(String alias) {
		if (alias == null || alias.isBlank()) {
			return;
		}
		if (!alias.matches(ALIAS_PATTERN)) {
			throw new InvalidAliasException(
					"Alias must be 3-10 characters and contain only letters, numbers, hyphens, or underscores");
		}
		if (ReservedPaths.isReserved(alias)) {
			throw new ReservedAliasException("Alias conflicts with a reserved system path");
		}
	}
}
