package com.test.kyc.identification.application.domain;

public enum KycStatus {
    SUBMITTED,
    DOCUMENT_VERIFICATION_PENDING,
    DOCUMENT_VERIFIED,
    DOCUMENT_REJECTED,
    LIVENESS_PENDING,
    LIVENESS_PASSED,
    LIVENESS_FAILED,
    WATCHLIST_SCREENING,
    WATCHLIST_CLEAR,
    WATCHLIST_HIT,
    MANUAL_REVIEW,
    APPROVED,
    REJECTED;

    public boolean isTerminal() {
        return this == APPROVED || this == REJECTED;
    }
}
