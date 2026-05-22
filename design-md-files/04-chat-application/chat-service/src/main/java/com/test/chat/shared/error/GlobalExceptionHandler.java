package com.test.chat.shared.error;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(ChatException.class)
	public ResponseEntity<ErrorResponse> handleChatException(ChatException ex, HttpServletRequest request) {
		return ResponseEntity.status(ex.getStatus()).body(error(ex.getCode(), ex.getMessage(), request, List.of()));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
		List<ErrorResponse.FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
				.map(this::toFieldError)
				.toList();
		return ResponseEntity.badRequest().body(error("VALIDATION_ERROR", "Request validation failed", request, fieldErrors));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleGeneric(Exception ex, HttpServletRequest request) {
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(error("INTERNAL_ERROR", "Unexpected server error", request, List.of()));
	}

	private ErrorResponse.FieldError toFieldError(FieldError fieldError) {
		return new ErrorResponse.FieldError(fieldError.getField(), fieldError.getDefaultMessage());
	}

	private ErrorResponse error(String code, String message, HttpServletRequest request, List<ErrorResponse.FieldError> errors) {
		return new ErrorResponse(code, message, requestId(request), Instant.now(), errors);
	}

	private String requestId(HttpServletRequest request) {
		Object existing = request.getAttribute("requestId");
		return existing != null ? existing.toString() : UUID.randomUUID().toString();
	}
}
