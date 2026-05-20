package com.test.notification.api.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.Map;

@Getter @Builder
public class ErrorResponse {
    private String code;
    private String message;
    private String requestId;
    private Instant timestamp;
    private Map<String, Object> details;
}
