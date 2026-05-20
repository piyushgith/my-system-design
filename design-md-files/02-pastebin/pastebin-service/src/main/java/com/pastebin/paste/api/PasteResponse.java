package com.pastebin.paste.api;

import com.pastebin.paste.application.PasteView;

import java.time.Instant;

public record PasteResponse(
        String id,
        String shortKey,
        String title,
        String content,
        String language,
        Instant expiresAt,
        String accessLevel,
        Long viewCount,
        long size,
        Instant createdAt,
        String contentUrl
) {
    static PasteResponse from(PasteView view) {
        String contentUrl = view.size() > 1_048_576 ? "/api/v1/pastes/" + view.shortKey() + "/content" : null;
        String content = contentUrl == null ? view.content() : null;
        return new PasteResponse(
                view.id(),
                view.shortKey(),
                view.title(),
                content,
                view.language(),
                view.expiresAt(),
                view.accessLevel().name(),
                view.viewCount(),
                view.size(),
                view.createdAt(),
                contentUrl
        );
    }
}
