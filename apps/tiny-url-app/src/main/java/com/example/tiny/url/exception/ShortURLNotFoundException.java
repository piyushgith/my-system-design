package com.example.tiny.url.exception;

public class ShortURLNotFoundException extends Throwable {
    public ShortURLNotFoundException(String shortUrlNotFound) {
        super(shortUrlNotFound);
    }
}
