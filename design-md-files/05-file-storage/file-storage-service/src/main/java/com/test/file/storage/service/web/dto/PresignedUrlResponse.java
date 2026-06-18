package com.test.file.storage.service.web.dto;

public record PresignedUrlResponse(String url, long expiresInSeconds) {
}
