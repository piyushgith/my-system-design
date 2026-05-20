package com.test.kyc.identification.common.exception;

import java.util.UUID;

public class ApplicationNotFoundException extends RuntimeException {
    public ApplicationNotFoundException(UUID applicationId) {
        super("KYC application not found: " + applicationId);
    }
}
