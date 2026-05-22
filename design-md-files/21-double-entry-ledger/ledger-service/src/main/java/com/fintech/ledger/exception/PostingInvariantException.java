package com.fintech.ledger.exception;

public class PostingInvariantException extends RuntimeException {
    private final long debitSum;
    private final long creditSum;
    private final String currency;

    public PostingInvariantException(long debitSum, long creditSum, String currency) {
        super("Debit sum (%d %s) != credit sum (%d %s). Imbalance: %d %s"
                .formatted(debitSum, currency, creditSum, currency, Math.abs(debitSum - creditSum), currency));
        this.debitSum = debitSum;
        this.creditSum = creditSum;
        this.currency = currency;
    }

    public long getDebitSum() { return debitSum; }
    public long getCreditSum() { return creditSum; }
    public String getCurrency() { return currency; }
}
