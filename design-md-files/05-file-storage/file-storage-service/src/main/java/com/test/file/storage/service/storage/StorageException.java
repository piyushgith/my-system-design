package com.test.file.storage.service.storage;

/** Wraps any failure originating from a storage backend so callers handle one exception type. */
public class StorageException extends RuntimeException {

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }

    public StorageException(String message) {
        super(message);
    }
}
