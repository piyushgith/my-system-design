package io.crm.common;

import java.util.List;

public record ErrorResponse(Error error) {

    public record Error(
            String code,
            String message,
            List<String> details,
            String correlationId
    ) {}

    public static ErrorResponse of(String code, String message, String correlationId) {
        return new ErrorResponse(new Error(code, message, List.of(), correlationId));
    }

    public static ErrorResponse of(String code, String message, List<String> details, String correlationId) {
        return new ErrorResponse(new Error(code, message, details, correlationId));
    }
}
