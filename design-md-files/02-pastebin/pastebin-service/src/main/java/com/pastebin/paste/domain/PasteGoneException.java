package com.pastebin.paste.domain;

public class PasteGoneException extends RuntimeException {
    public PasteGoneException(String message) {
        super(message);
    }
}
