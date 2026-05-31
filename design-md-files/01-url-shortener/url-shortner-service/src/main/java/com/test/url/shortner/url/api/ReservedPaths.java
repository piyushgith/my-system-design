package com.test.url.shortner.url.api;

import java.util.Set;

public final class ReservedPaths {

	private static final Set<String> RESERVED = Set.of(
			"api", "health", "metrics", "admin", "auth", "actuator", "swagger", "swagger-ui");

	private ReservedPaths() {
	}

	public static boolean isReserved(String value) {
		return value != null && RESERVED.contains(value.toLowerCase());
	}
}
