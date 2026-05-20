package com.pastebin.paste.application;

import com.pastebin.shared.AccessLevel;

import java.time.Instant;

public record PasteSummary(
        String shortKey,
        String title,
        String language,
        AccessLevel accessLevel,
        long viewCount,
        long size,
        Instant expiresAt,
        Instant createdAt
) {
}
