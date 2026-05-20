package com.pastebin.paste.application;

import com.pastebin.shared.AccessLevel;
import com.pastebin.shared.ExpiryPolicy;

import java.time.Instant;

public record CreatePasteResult(
        String id,
        String shortKey,
        String url,
        String rawUrl,
        String language,
        Instant expiresAt,
        AccessLevel accessLevel,
        Instant createdAt,
        long size,
        boolean idempotentReplay
) {
}
