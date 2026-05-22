package com.test.url.shortner.url.infrastructure;

import com.test.url.shortner.url.domain.UrlStatus;
import java.time.Instant;

public record CachedUrlEntry(String longUrl, UrlStatus status, Instant expiresAt) {

	public boolean isRedirectable() {
		if (status != UrlStatus.ACTIVE) {
			return false;
		}
		return expiresAt == null || Instant.now().isBefore(expiresAt);
	}
}
