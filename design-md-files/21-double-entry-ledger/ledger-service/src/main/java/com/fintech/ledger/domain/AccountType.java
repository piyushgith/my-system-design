package com.fintech.ledger.domain;

public enum AccountType {
    ASSET, LIABILITY, EQUITY, INCOME, EXPENSE;

    public Direction normalBalance() {
        return switch (this) {
            case ASSET, EXPENSE -> Direction.DEBIT;
            case LIABILITY, EQUITY, INCOME -> Direction.CREDIT;
        };
    }
}
