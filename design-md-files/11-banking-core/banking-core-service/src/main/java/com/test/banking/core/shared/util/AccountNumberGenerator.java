package com.test.banking.core.shared.util;

public final class AccountNumberGenerator {

    private AccountNumberGenerator() {
    }

    public static String savingsAccountId(long sequence) {
        String base = String.format("SAV-%08d", sequence);
        int check = luhnCheckDigit(base.replace("SAV-", ""));
        return base + check;
    }

    public static boolean isValidSavingsAccountId(String accountId) {
        if (accountId == null || !accountId.matches("SAV-\\d{8}\\d")) {
            return false;
        }
        String numericPart = accountId.substring(4, 12);
        int expectedCheck = Character.getNumericValue(accountId.charAt(12));
        return luhnCheckDigit(numericPart) == expectedCheck;
    }

    private static int luhnCheckDigit(String numericPart) {
        int sum = 0;
        boolean alternate = true;
        for (int i = numericPart.length() - 1; i >= 0; i--) {
            int n = Character.getNumericValue(numericPart.charAt(i));
            if (alternate) {
                n *= 2;
                if (n > 9) {
                    n -= 9;
                }
            }
            sum += n;
            alternate = !alternate;
        }
        return (10 - (sum % 10)) % 10;
    }
}
