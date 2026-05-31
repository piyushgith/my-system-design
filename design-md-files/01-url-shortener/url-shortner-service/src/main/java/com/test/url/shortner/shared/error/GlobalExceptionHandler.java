package com.test.url.shortner.shared.error;

import com.test.url.shortner.url.api.exception.AliasConflictException;
import com.test.url.shortner.url.api.exception.InvalidAliasException;
import com.test.url.shortner.url.api.exception.InvalidLongUrlException;
import com.test.url.shortner.url.api.exception.ReservedAliasException;
import com.test.url.shortner.url.api.exception.ShortCodeGenerationException;
import java.util.stream.Collectors;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.NonNull;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final String FIELD_ALIAS = "alias";

	@ExceptionHandler(InvalidLongUrlException.class)
	public ResponseEntity<ErrorResponse> handleInvalidLongUrl(InvalidLongUrlException ex) {
		return error(HttpStatus.BAD_REQUEST, "INVALID_URL", ex.getMessage(), "longUrl");
	}

	@ExceptionHandler(InvalidAliasException.class)
	public ResponseEntity<ErrorResponse> handleInvalidAlias(InvalidAliasException ex) {
		return error(HttpStatus.BAD_REQUEST, "INVALID_ALIAS", ex.getMessage(), FIELD_ALIAS);
	}

	@ExceptionHandler(ReservedAliasException.class)
	public ResponseEntity<ErrorResponse> handleReservedAlias(ReservedAliasException ex) {
		return error(HttpStatus.BAD_REQUEST, "RESERVED_ALIAS", ex.getMessage(), FIELD_ALIAS);
	}

	@ExceptionHandler(AliasConflictException.class)
	public ResponseEntity<ErrorResponse> handleAliasConflict(AliasConflictException ex) {
		return error(HttpStatus.CONFLICT, "ALIAS_CONFLICT", ex.getMessage(), FIELD_ALIAS);
	}

	@ExceptionHandler(ShortCodeGenerationException.class)
	public ResponseEntity<ErrorResponse> handleShortCodeGeneration(ShortCodeGenerationException ex) {
		return error(HttpStatus.INTERNAL_SERVER_ERROR, "SHORT_CODE_GENERATION_FAILED", ex.getMessage(), null);
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
		String message = ex.getBindingResult().getFieldErrors().stream()
				.map(e -> e.getField() + ": " + e.getDefaultMessage())
				.collect(Collectors.joining("; "));
		String field = ex.getBindingResult().getFieldErrors().isEmpty()
				? null
				: ex.getBindingResult().getFieldErrors().getFirst().getField();
		return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, field);
	}

	private ResponseEntity<ErrorResponse> error(@NonNull HttpStatus status, String code, String message, String field) {
		return ResponseEntity.status(status)
				.header("X-Trace-Id", MDC.get("traceId"))
				.body(new ErrorResponse(code, message, field));
	}
}
