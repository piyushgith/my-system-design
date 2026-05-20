package com.pastebin.paste.application;

import com.pastebin.shared.AccessLevel;
import com.pastebin.shared.ExpiryPolicy;

import java.time.Instant;

public record CreatePasteCommand(
        String title,
        String content,
        String language,
        ExpiryPolicy expiryPolicy,
        AccessLevel accessLevel,
        String password,
        String idempotencyKey
) {
}
