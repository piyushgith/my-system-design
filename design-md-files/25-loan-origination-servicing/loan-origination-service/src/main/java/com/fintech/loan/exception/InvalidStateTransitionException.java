package com.fintech.loan.exception;

public class InvalidStateTransitionException extends RuntimeException {
    public InvalidStateTransitionException(String entityType, String fromState, String toState) {
        super("Invalid state transition for " + entityType + ": " + fromState + " → " + toState);
    }
}
