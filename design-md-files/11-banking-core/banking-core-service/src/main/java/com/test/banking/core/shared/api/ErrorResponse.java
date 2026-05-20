package com.test.banking.core.shared.api;

import java.time.Instant;
import java.util.Map;

public record ErrorResponse(ErrorBody error) {

    public record ErrorBody(
            String code,
            String message,
            Map<String, Object> details,
            String correlationId,
            Instant timestamp,
            String path) {
    }

    public static ErrorResponse of(String code, String message, String correlationId, String path) {
        return new ErrorResponse(new ErrorBody(code, message, null, correlationId, Instant.now(), path));
    }

    public static ErrorResponse of(String code, String message, Map<String, Object> details,
                                   String correlationId, String path) {
        return new ErrorResponse(new ErrorBody(code, message, details, correlationId, Instant.now(), path));
    }
}
