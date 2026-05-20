package com.pastebin.paste.infrastructure.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pastebin.cache")
public record PasteCacheProperties(long negativeTtlSeconds, long maxNeverExpireTtlSeconds) {
}
