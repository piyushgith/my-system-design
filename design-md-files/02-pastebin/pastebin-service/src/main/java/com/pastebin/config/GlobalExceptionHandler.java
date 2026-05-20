package com.pastebin.config;

import com.pastebin.identity.domain.IdentityDomainException;
import com.pastebin.paste.domain.DomainException;
import com.pastebin.paste.domain.PasteGoneException;
import com.pastebin.paste.domain.PasteNotAccessibleException;
import com.pastebin.paste.domain.PasteNotFoundException;
import com.pastebin.paste.domain.PastePasswordRequiredException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(PasteNotFoundException.class)
    ResponseEntity<ProblemDetail> handleNotFound(PasteNotFoundException ex, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "paste-not-found", ex.getMessage(), request);
    }

    @ExceptionHandler(PasteGoneException.class)
    ResponseEntity<ProblemDetail> handleGone(PasteGoneException ex, HttpServletRequest request) {
        return problem(HttpStatus.GONE, "paste-gone", ex.getMessage(), request);
    }

    @ExceptionHandler({PasteNotAccessibleException.class, PastePasswordRequiredException.class})
    ResponseEntity<ProblemDetail> handleForbidden(RuntimeException ex, HttpServletRequest request) {
        HttpStatus status = ex instanceof PastePasswordRequiredException ? HttpStatus.FORBIDDEN : HttpStatus.FORBIDDEN;
        return problem(status, "paste-forbidden", ex.getMessage(), request);
    }

    @ExceptionHandler({IdentityDomainException.class, DomainException.class})
    ResponseEntity<ProblemDetail> handleBadRequest(RuntimeException ex, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "bad-request", ex.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse("Validation failed");
        return problem(HttpStatus.BAD_REQUEST, "validation-failed", detail, request);
    }

    @ExceptionHandler({AccessDeniedException.class, BadCredentialsException.class})
    ResponseEntity<ProblemDetail> handleUnauthorized(RuntimeException ex, HttpServletRequest request) {
        return problem(HttpStatus.UNAUTHORIZED, "unauthorized", ex.getMessage(), request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleGeneric(Exception ex, HttpServletRequest request) {
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "An unexpected error occurred", request);
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status,
                                                   String type,
                                                   String detail,
                                                   HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("https://api.pastebin.io/problems/" + type));
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("instance", request.getRequestURI());
        problem.setProperty("traceId", MDC.get("traceId"));
        return ResponseEntity.status(status).body(problem);
    }
}
