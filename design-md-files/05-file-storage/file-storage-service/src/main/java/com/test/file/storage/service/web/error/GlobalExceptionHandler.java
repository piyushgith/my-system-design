package com.test.file.storage.service.web.error;

import com.test.file.storage.service.storage.StorageException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.net.URI;

/** Maps domain and framework exceptions to RFC 7807 Problem Details responses. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail handleNotFound(ResourceNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Resource not found", ex.getMessage(), "not-found");
    }

    @ExceptionHandler(InvalidUploadStateException.class)
    public ProblemDetail handleInvalidState(InvalidUploadStateException ex) {
        return problem(HttpStatus.CONFLICT, "Invalid upload state", ex.getMessage(), "invalid-upload-state");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ProblemDetail handleTooLarge(MaxUploadSizeExceededException ex) {
        return problem(HttpStatus.CONTENT_TOO_LARGE, "File too large", ex.getMessage(), "file-too-large");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Validation failed");
        return problem(HttpStatus.BAD_REQUEST, "Validation failed", detail, "validation");
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ProblemDetail handleConcurrency(OptimisticLockingFailureException ex) {
        return problem(HttpStatus.CONFLICT, "Concurrent modification",
                "The resource was modified concurrently; retry the request.", "concurrent-modification");
    }

    @ExceptionHandler(StorageException.class)
    public ProblemDetail handleStorage(StorageException ex) {
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Storage error", ex.getMessage(), "storage");
    }

    @ExceptionHandler(UnsupportedOperationException.class)
    public ProblemDetail handleUnsupported(UnsupportedOperationException ex) {
        return problem(HttpStatus.NOT_IMPLEMENTED, "Not supported", ex.getMessage(), "not-supported");
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail, String slug) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(title);
        pd.setType(URI.create("https://api.example.com/errors/" + slug));
        return pd;
    }
}
