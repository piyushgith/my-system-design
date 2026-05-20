package com.test.kyc.identification.common.exception;

import java.util.UUID;

public class ReviewAlreadyDecidedException extends RuntimeException {
    public ReviewAlreadyDecidedException(UUID applicationId) {
        super("Application %s has already been decided".formatted(applicationId));
    }
}
