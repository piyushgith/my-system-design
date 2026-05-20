package com.test.banking.core.shared.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.test.banking.core.shared.exception.BusinessRuleException;

public final class RequestFingerprint {

    private RequestFingerprint() {
    }

    public static String of(ObjectMapper objectMapper, Object request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (Exception e) {
            throw new BusinessRuleException("FINGERPRINT_ERROR",
                    "Unable to fingerprint request for idempotency");
        }
    }
}
