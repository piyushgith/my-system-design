package com.test.banking.core.shared.validation;

import com.test.banking.core.shared.exception.BusinessRuleException;

import java.util.regex.Pattern;

public final class PanValidator {

    private static final Pattern PAN_PATTERN = Pattern.compile("^[A-Z]{5}[0-9]{4}[A-Z]$");

    private PanValidator() {
    }

    public static String normalizeAndValidate(String pan) {
        if (pan == null || pan.isBlank()) {
            throw new BusinessRuleException("INVALID_PAN", "PAN is required");
        }
        String normalized = pan.trim().toUpperCase();
        if (!PAN_PATTERN.matcher(normalized).matches()) {
            throw new BusinessRuleException("INVALID_PAN", "PAN must match format AAAAA9999A");
        }
        return normalized;
    }
}
