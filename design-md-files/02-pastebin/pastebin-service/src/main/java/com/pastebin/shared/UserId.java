package com.pastebin.shared;

import java.util.UUID;

public record UserId(UUID value) {
    public UserId {
        if (value == null) {
            throw new IllegalArgumentException("UserId cannot be null");
        }
    }

    public static UserId of(String id) {
        return new UserId(UUID.fromString(id));
    }

    public static UserId of(UUID id) {
        return new UserId(id);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
