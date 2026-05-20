package com.test.banking.core.shared.exception;

public class NotFoundException extends DomainException {

    public NotFoundException(String message) {
        super("NOT_FOUND", message);
    }
}
