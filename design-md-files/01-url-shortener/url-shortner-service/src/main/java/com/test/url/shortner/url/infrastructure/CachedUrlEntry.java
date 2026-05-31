package com.test.url.shortner.url.infrastructure;

import com.test.url.shortner.url.domain.UrlStatus;
import java.time.Instant;

public record CachedUrlEntry(String longUrl, UrlStatus status, Instant expiresAt) {
}
