package com.example.tiny.url.config;

import com.example.tiny.url.exception.ShortURLNotFoundException;
import org.springdoc.api.ErrorMessage;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

@RestControllerAdvice
public class MyControllerAdvice {

    @ExceptionHandler(ShortURLNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorMessage handleShortUrlNotFound(ShortURLNotFoundException ex, WebRequest request) {
        return new ErrorMessage(ex.getMessage());
    }

}
