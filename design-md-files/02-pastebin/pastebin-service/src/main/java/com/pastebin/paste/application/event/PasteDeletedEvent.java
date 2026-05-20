package com.pastebin.paste.application.event;

import com.pastebin.shared.DeletionReason;
import com.pastebin.shared.PasteId;
import com.pastebin.shared.ShortKey;

public record PasteDeletedEvent(PasteId pasteId, ShortKey shortKey, String contentS3Key, DeletionReason reason) {
}
