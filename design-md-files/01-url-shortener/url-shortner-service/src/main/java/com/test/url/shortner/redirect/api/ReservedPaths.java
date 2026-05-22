package com.test.url.shortner.redirect.api;

import java.util.Set;

final class ReservedPaths {

	private static final Set<String> RESERVED = Set.of(
			"api", "health", "metrics", "admin", "auth", "actuator", "swagger", "swagger-ui");

	private ReservedPaths() {
	}

	static boolean isReserved(String shortCode) {
		return shortCode != null && RESERVED.contains(shortCode.toLowerCase());
	}
}
