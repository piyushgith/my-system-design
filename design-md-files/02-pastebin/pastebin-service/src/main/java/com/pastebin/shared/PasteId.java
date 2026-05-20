package com.pastebin.shared;

import java.util.UUID;

public record PasteId(UUID value) {
    public PasteId {
        if (value == null) {
            throw new IllegalArgumentException("PasteId cannot be null");
        }
    }

    public static PasteId generate() {
        return new PasteId(UUID.randomUUID());
    }

    public static PasteId of(String id) {
        return new PasteId(UUID.fromString(id));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
