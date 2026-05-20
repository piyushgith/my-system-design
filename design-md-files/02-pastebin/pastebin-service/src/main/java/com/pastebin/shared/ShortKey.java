package com.pastebin.shared;

public record ShortKey(String value) {
    private static final int MIN_LENGTH = 4;
    private static final int MAX_LENGTH = 16;

    public ShortKey {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("ShortKey cannot be blank");
        }
        if (value.length() < MIN_LENGTH || value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("ShortKey length must be between 4 and 16");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
