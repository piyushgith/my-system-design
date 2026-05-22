package com.fintech.ledger.domain;

public enum AccountStatus {
    ACTIVE, FROZEN, CLOSED;

    public boolean acceptsPostings() {
        return this == ACTIVE;
    }
}
