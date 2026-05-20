package com.test.banking.core.shared.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountNumberGeneratorTest {

    @Test
    void validatesGeneratedAccountId() {
        String accountId = AccountNumberGenerator.savingsAccountId(42);
        assertTrue(AccountNumberGenerator.isValidSavingsAccountId(accountId));
    }

    @Test
    void rejectsTamperedCheckDigit() {
        String accountId = AccountNumberGenerator.savingsAccountId(42);
        String tampered = accountId.substring(0, 12) + ((accountId.charAt(12) - '0' + 1) % 10);
        assertFalse(AccountNumberGenerator.isValidSavingsAccountId(tampered));
    }
}
