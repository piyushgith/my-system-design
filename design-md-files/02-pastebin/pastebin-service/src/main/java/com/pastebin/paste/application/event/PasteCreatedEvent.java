package com.pastebin.paste.application.event;

import com.pastebin.shared.DeletionReason;
import com.pastebin.shared.PasteId;

import java.time.Instant;

public record PasteCreatedEvent(PasteId pasteId, Instant expiresAt) {
}
