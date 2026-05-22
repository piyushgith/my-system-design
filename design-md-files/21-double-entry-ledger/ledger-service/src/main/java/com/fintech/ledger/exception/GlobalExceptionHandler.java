package com.fintech.ledger.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(AccountNotFoundException ex) {
        return error(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", ex.getMessage(), null);
    }

    @ExceptionHandler(PostingNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handlePostingNotFound(PostingNotFoundException ex) {
        return error(HttpStatus.NOT_FOUND, "POSTING_NOT_FOUND", ex.getMessage(), null);
    }

    @ExceptionHandler(AccountNotActiveException.class)
    public ResponseEntity<Map<String, Object>> handleFrozenClosed(AccountNotActiveException ex) {
        return error(HttpStatus.CONFLICT, "ACCOUNT_NOT_ACTIVE", ex.getMessage(), null);
    }

    @ExceptionHandler(PostingInvariantException.class)
    public ResponseEntity<Map<String, Object>> handleInvariant(PostingInvariantException ex) {
        return error(HttpStatus.BAD_REQUEST, "POSTING_INVARIANT_VIOLATION", ex.getMessage(),
                Map.of("debit_sum", ex.getDebitSum(),
                        "credit_sum", ex.getCreditSum(),
                        "currency", ex.getCurrency()));
    }

    @ExceptionHandler(PostingAlreadyReversedException.class)
    public ResponseEntity<Map<String, Object>> handleAlreadyReversed(PostingAlreadyReversedException ex) {
        return error(HttpStatus.CONFLICT, "POSTING_ALREADY_REVERSED", ex.getMessage(), null);
    }

    // Duplicate idempotency_key at DB level (race condition past Redis check)
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDataIntegrity(DataIntegrityViolationException ex) {
        return error(HttpStatus.CONFLICT, "DUPLICATE_IDEMPOTENCY_KEY",
                "Posting with this idempotency key already exists", null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .findFirst().orElse("Validation failed");
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
        log.error("Unhandled exception", ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected error", null);
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String code,
                                                        String message, Map<String, Object> details) {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("error_code", code);
        body.put("message", message);
        body.put("request_id", UUID.randomUUID().toString());
        body.put("timestamp", Instant.now().toString());
        if (details != null) body.put("details", details);
        return ResponseEntity.status(status).body(body);
    }
}
