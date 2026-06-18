package com.test.ride.sharing.service.web.error;

import java.util.Map;

public class BusinessRuleException extends RuntimeException {

    private final String code;
    private final Map<String, Object> details;

    public BusinessRuleException(String code, String message) {
        this(code, message, Map.of());
    }

    public BusinessRuleException(String code, String message, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.details = details == null ? Map.of() : details;
    }

    public String getCode() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }
}
