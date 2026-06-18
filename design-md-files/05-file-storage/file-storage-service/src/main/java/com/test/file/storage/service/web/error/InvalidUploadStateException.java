package com.test.file.storage.service.web.error;

/** Thrown when an operation is attempted on an upload session in an incompatible state. Maps to HTTP 409. */
public class InvalidUploadStateException extends RuntimeException {

    public InvalidUploadStateException(String message) {
        super(message);
    }
}
