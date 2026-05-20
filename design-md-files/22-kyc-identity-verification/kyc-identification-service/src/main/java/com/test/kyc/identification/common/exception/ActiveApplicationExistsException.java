package com.test.kyc.identification.common.exception;

import java.util.UUID;

public class ActiveApplicationExistsException extends RuntimeException {
    public ActiveApplicationExistsException(UUID userId) {
        super("User %s already has an active KYC application in progress".formatted(userId));
    }
}
