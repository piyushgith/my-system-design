package com.pastebin.paste.api;

import com.pastebin.paste.application.CreatePasteResult;
import com.pastebin.shared.AccessLevel;

import java.time.Instant;

public record CreatePasteResponse(
        String id,
        String shortKey,
        String url,
        String rawUrl,
        String language,
        Instant expiresAt,
        AccessLevel accessLevel,
        Instant createdAt,
        long size
) {
    static CreatePasteResponse from(CreatePasteResult result) {
        return new CreatePasteResponse(
                result.id(), result.shortKey(), result.url(), result.rawUrl(),
                result.language(), result.expiresAt(), result.accessLevel(),
                result.createdAt(), result.size()
        );
    }
}
