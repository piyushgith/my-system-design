package com.test.url.shortner.shared.error;

import com.test.url.shortner.url.application.AliasValidator.InvalidAliasException;
import com.test.url.shortner.url.application.AliasValidator.ReservedAliasException;
import com.test.url.shortner.url.application.LongUrlValidator.InvalidLongUrlException;
import com.test.url.shortner.url.application.UrlCreationService.AliasConflictException;
import com.test.url.shortner.url.application.UrlCreationService.ShortCodeGenerationException;
import java.util.stream.Collectors;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(InvalidLongUrlException.class)
	public ResponseEntity<ErrorResponse> handleInvalidLongUrl(InvalidLongUrlException ex) {
		return error(HttpStatus.BAD_REQUEST, "INVALID_URL", ex.getMessage(), "longUrl");
	}

	@ExceptionHandler(InvalidAliasException.class)
	public ResponseEntity<ErrorResponse> handleInvalidAlias(InvalidAliasException ex) {
		return error(HttpStatus.BAD_REQUEST, "INVALID_ALIAS", ex.getMessage(), "alias");
	}

	@ExceptionHandler(ReservedAliasException.class)
	public ResponseEntity<ErrorResponse> handleReservedAlias(ReservedAliasException ex) {
		return error(HttpStatus.BAD_REQUEST, "RESERVED_ALIAS", ex.getMessage(), "alias");
	}

	@ExceptionHandler(AliasConflictException.class)
	public ResponseEntity<ErrorResponse> handleAliasConflict(AliasConflictException ex) {
		return error(HttpStatus.CONFLICT, "ALIAS_CONFLICT", ex.getMessage(), "alias");
	}

	@ExceptionHandler(ShortCodeGenerationException.class)
	public ResponseEntity<ErrorResponse> handleShortCodeGeneration(ShortCodeGenerationException ex) {
		return error(HttpStatus.INTERNAL_SERVER_ERROR, "SHORT_CODE_GENERATION_FAILED", ex.getMessage(), null);
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
		String message = ex.getBindingResult().getFieldErrors().stream()
				.map(error -> error.getField() + ": " + error.getDefaultMessage())
				.collect(Collectors.joining("; "));
		String field = ex.getBindingResult().getFieldErrors().isEmpty()
				? null
				: ex.getBindingResult().getFieldErrors().getFirst().getField();
		return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, field);
	}

	private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message, String field) {
		ErrorResponse body = new ErrorResponse(code, message, field);
		return ResponseEntity.status(status)
				.header("X-Trace-Id", MDC.get("traceId"))
				.body(body);
	}
}
