package com.pastebin.paste.api;

import com.pastebin.paste.application.PasteListResult;
import com.pastebin.paste.application.PasteSummary;

import java.time.Instant;
import java.util.List;

public record PasteListResponse(List<PasteSummaryResponse> items, String cursor, boolean hasMore) {
    static PasteListResponse from(PasteListResult result) {
        return new PasteListResponse(
                result.items().stream().map(PasteSummaryResponse::from).toList(),
                result.cursor(),
                result.hasMore()
        );
    }
}

record PasteSummaryResponse(
        String shortKey,
        String title,
        String language,
        String accessLevel,
        long viewCount,
        long size,
        Instant expiresAt,
        Instant createdAt
) {
    static PasteSummaryResponse from(PasteSummary summary) {
        return new PasteSummaryResponse(
                summary.shortKey(),
                summary.title(),
                summary.language(),
                summary.accessLevel().name(),
                summary.viewCount(),
                summary.size(),
                summary.expiresAt(),
                summary.createdAt()
        );
    }
}
