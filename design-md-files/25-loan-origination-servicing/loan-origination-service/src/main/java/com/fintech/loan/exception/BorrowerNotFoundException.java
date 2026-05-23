package com.fintech.loan.exception;

import java.util.UUID;

public class BorrowerNotFoundException extends RuntimeException {
    public BorrowerNotFoundException(UUID borrowerId) {
        super("Borrower not found: " + borrowerId);
    }
}
