package com.test.ride.sharing.service.web.error;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;
import com.test.ride.sharing.service.shared.Uuids;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(ResourceNotFoundException ex) {
        return error(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage(), null);
    }

    @ExceptionHandler(BusinessRuleException.class)
    public ResponseEntity<Map<String, Object>> handleBusiness(BusinessRuleException ex) {
        HttpStatus status = switch (ex.getCode()) {
            case "TRIP_ALREADY_ACTIVE", "DRIVER_ALREADY_MATCHED", "IDEMPOTENCY_CONFLICT", "INVALID_STATE_TRANSITION" ->
                    HttpStatus.CONFLICT;
            case "NO_DRIVERS_AVAILABLE" -> HttpStatus.UNPROCESSABLE_ENTITY;
            case "EXPIRED_QUOTE", "INVALID_OTP", "INVALID_LOCATION" -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.BAD_REQUEST;
        };
        return error(status, ex.getCode(), ex.getMessage(), ex.details().isEmpty() ? null : ex.details());
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<Map<String, Object>> handleUnauthorized(UnauthorizedException ex) {
        return error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", ex.getMessage(), null);
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Map<String, Object>> handleForbidden(ForbiddenException ex) {
        return error(HttpStatus.FORBIDDEN, "FORBIDDEN", ex.getMessage(), null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Validation failed");
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", detail, null);
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String code, String message,
                                                        Map<String, Object> details) {
        Map<String, Object> body = Map.of(
                "error", Map.of(
                        "code", code,
                        "message", message,
                        "details", details == null ? Map.of() : details,
                        "request_id", "req_" + Uuids.v7().toString().substring(0, 8),
                        "timestamp", Instant.now().toString()
                )
        );
        return ResponseEntity.status(status).body(body);
    }
}
