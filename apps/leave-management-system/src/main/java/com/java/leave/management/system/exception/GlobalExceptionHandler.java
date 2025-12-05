package com.java.leave.management.system.exception;

import com.java.leave.management.system.dto.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(Exception.class)
    public Mono<ResponseEntity<ApiResponse<String>>> handleGenericException(Exception ex) {
        ApiResponse<String> response = new ApiResponse<>(false, ex.getMessage());
        return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response));
    }

    @ExceptionHandler(RuntimeException.class)
    public Mono<ResponseEntity<ApiResponse<String>>> handleRuntimeException(RuntimeException ex) {
        ApiResponse<String> response = new ApiResponse<>(false, ex.getMessage());
        return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response));
    }

    @ExceptionHandler(JwtAuthenticationException.class)
    public Mono<ResponseEntity<ApiResponse<String>>> handleJwtAuthenticationException(JwtAuthenticationException ex) {
        ApiResponse<String> response = new ApiResponse<>(false, ex.getMessage());
        return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response));
    }

    @ExceptionHandler(ValidationException.class)
    public Mono<ResponseEntity<ApiResponse<String>>> handleValidationException(ValidationException ex) {
        ApiResponse<String> response = new ApiResponse<>(false, ex.getMessage());
        return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response));
    }

    @ExceptionHandler(WebExchangeBindException.class)
    public Mono<ResponseEntity<ApiResponse<Map<String, String>>>> handleValidationException(WebExchangeBindException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error ->
            errors.put(error.getField(), error.getDefaultMessage())
        );
        
        ApiResponse<Map<String, String>> response = new ApiResponse<>(false, "Validation failed", errors);
        return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response));
    }

    @ExceptionHandler(ServerWebInputException.class)
    public Mono<ResponseEntity<ApiResponse<String>>> handleServerWebInputException(ServerWebInputException ex) {
        ApiResponse<String> response = new ApiResponse<>(false, ex.getReason());
        return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response));
    }
}