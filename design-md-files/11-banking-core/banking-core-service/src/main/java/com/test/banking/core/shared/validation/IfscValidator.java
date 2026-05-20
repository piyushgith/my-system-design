package com.test.banking.core.shared.validation;

import com.test.banking.core.shared.exception.BusinessRuleException;

import java.util.regex.Pattern;

public final class IfscValidator {

    private static final Pattern IFSC_PATTERN = Pattern.compile("^[A-Z]{4}0[A-Z0-9]{6}$");

    private IfscValidator() {
    }

    public static String normalizeAndValidate(String ifsc) {
        if (ifsc == null || ifsc.isBlank()) {
            throw new BusinessRuleException("INVALID_IFSC", "IFSC code is required");
        }
        String normalized = ifsc.trim().toUpperCase();
        if (!IFSC_PATTERN.matcher(normalized).matches()) {
            throw new BusinessRuleException("INVALID_IFSC",
                    "IFSC must be 11 characters: 4 letters + 0 + 6 alphanumeric (e.g. HDFC0001234)");
        }
        return normalized;
    }

    public static boolean isValid(String ifsc) {
        if (ifsc == null || ifsc.isBlank()) {
            return false;
        }
        return IFSC_PATTERN.matcher(ifsc.trim().toUpperCase()).matches();
    }
}
