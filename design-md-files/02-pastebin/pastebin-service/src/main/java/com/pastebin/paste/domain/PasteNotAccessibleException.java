package com.pastebin.paste.domain;

public class PasteNotAccessibleException extends RuntimeException {
    public PasteNotAccessibleException(String message) {
        super(message);
    }
}
