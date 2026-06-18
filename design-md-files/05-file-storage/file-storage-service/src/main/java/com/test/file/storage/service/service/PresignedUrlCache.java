package com.test.file.storage.service.service;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process cache for presigned download URLs. Avoids a MinIO API round-trip on every
 * download request for the same file. A 30-second safety buffer prevents serving URLs
 * that expire mid-flight to a slow client.
 */
@Component
public class PresignedUrlCache {

    private static final Duration SAFETY_BUFFER = Duration.ofSeconds(30);

    private record Entry(String url, Instant expiresAt) {}

    private final ConcurrentHashMap<String, Entry> cache = new ConcurrentHashMap<>();

    public Optional<String> get(String fileId) {
        Entry entry = cache.get(fileId);
        if (entry == null) {
            return Optional.empty();
        }
        if (Instant.now().isAfter(entry.expiresAt().minus(SAFETY_BUFFER))) {
            cache.remove(fileId);
            return Optional.empty();
        }
        return Optional.of(entry.url());
    }

    public void put(String fileId, String url, Duration ttl) {
        cache.put(fileId, new Entry(url, Instant.now().plus(ttl)));
    }

    public void evict(String fileId) {
        cache.remove(fileId);
    }
}
