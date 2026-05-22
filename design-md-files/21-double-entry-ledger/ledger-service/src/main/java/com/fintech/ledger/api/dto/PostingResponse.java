package com.fintech.ledger.api.dto;

import com.fintech.ledger.domain.Posting;
import com.fintech.ledger.domain.PostingStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PostingResponse(
        UUID postingId,
        String idempotencyKey,
        String referenceType,
        UUID referenceId,
        PostingStatus status,
        UUID reversalOf,
        Instant effectiveAt,
        Instant createdAt,
        String description,
        List<JournalEntryResponse> legs
) {
    public static PostingResponse from(Posting p) {
        return new PostingResponse(
                p.getPostingId(), p.getIdempotencyKey(), p.getReferenceType(),
                p.getReferenceId(), p.getStatus(), p.getReversalOf(),
                p.getEffectiveAt(), p.getCreatedAt(), p.getDescription(),
                p.getLegs().stream().map(JournalEntryResponse::from).toList());
    }
}
