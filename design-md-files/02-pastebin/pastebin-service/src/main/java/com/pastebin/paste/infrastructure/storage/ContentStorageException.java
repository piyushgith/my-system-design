package com.pastebin.paste.infrastructure.storage;

public class ContentStorageException extends RuntimeException {
    public ContentStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
