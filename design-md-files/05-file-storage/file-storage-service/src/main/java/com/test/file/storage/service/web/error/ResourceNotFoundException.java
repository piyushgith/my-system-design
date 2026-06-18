package com.test.file.storage.service.web.error;

/** Thrown when a requested file, folder, or upload session does not exist. Maps to HTTP 404. */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
