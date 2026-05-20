package com.pastebin.paste.application;

import com.pastebin.shared.AccessLevel;
import com.pastebin.shared.ContentType;
import com.pastebin.shared.ExpiryPolicy;

import java.time.Instant;

public record PasteView(
        String id,
        String shortKey,
        String title,
        String content,
        String language,
        ContentType contentType,
        String contentS3Key,
        long size,
        ExpiryPolicy expiryPolicy,
        Instant expiresAt,
        AccessLevel accessLevel,
        boolean passwordProtected,
        Long viewCount,
        Instant createdAt,
        String ownerDisplayName
) {
}
