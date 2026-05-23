package com.fintech.loan.exception;

public class DuplicatePaymentException extends RuntimeException {
    public DuplicatePaymentException(String idempotencyKey) {
        super("Payment already recorded for idempotency key: " + idempotencyKey);
    }
}
