package com.fintech.loan.exception;

import java.util.UUID;

public class LoanNotFoundException extends RuntimeException {
    public LoanNotFoundException(UUID loanAccountId) {
        super("Loan account not found: " + loanAccountId);
    }

    public LoanNotFoundException(String loanAccountNumber) {
        super("Loan account not found: " + loanAccountNumber);
    }
}
