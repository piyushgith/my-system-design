package com.test.banking.core.shared.validation;

import com.test.banking.core.shared.exception.BusinessRuleException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IfscValidatorTest {

    @Test
    void acceptsValidIfsc() {
        assertEquals("HDFC0001234", IfscValidator.normalizeAndValidate("hdfc0001234"));
    }

    @Test
    void rejectsInvalidIfsc() {
        assertThrows(BusinessRuleException.class, () -> IfscValidator.normalizeAndValidate("INVALID"));
    }
}
