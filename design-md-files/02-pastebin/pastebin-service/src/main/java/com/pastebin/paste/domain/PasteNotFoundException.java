package com.pastebin.paste.domain;

public class PasteNotFoundException extends RuntimeException {
    public PasteNotFoundException(String message) {
        super(message);
    }
}
