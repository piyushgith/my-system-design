package com.pastebin.paste.api;

import com.pastebin.paste.application.CreatePasteCommand;
import com.pastebin.shared.AccessLevel;
import com.pastebin.shared.ExpiryPolicy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreatePasteRequest(
        @Size(max = 255) String title,
        @NotBlank String content,
        String language,
        ExpiryPolicy expiryPolicy,
        AccessLevel accessLevel,
        @Size(max = 72) String password
) {
    CreatePasteCommand toCommand(String idempotencyKey) {
        return new CreatePasteCommand(title, content, language, expiryPolicy, accessLevel, password, idempotencyKey);
    }
}
