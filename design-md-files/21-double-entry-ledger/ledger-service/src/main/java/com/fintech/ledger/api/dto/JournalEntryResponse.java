package com.fintech.ledger.api.dto;

import com.fintech.ledger.domain.Direction;
import com.fintech.ledger.domain.JournalEntry;

import java.util.UUID;

public record JournalEntryResponse(
        UUID entryId,
        UUID accountId,
        Direction direction,
        Long amount,
        String currency,
        String description
) {
    public static JournalEntryResponse from(JournalEntry e) {
        return new JournalEntryResponse(
                e.getEntryId(), e.getAccountId(), e.getDirection(),
                e.getAmount(), e.getCurrency(), e.getDescription());
    }
}
