package com.pastebin.paste.domain;

public class PastePasswordRequiredException extends RuntimeException {
    public PastePasswordRequiredException(String message) {
        super(message);
    }
}
