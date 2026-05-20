package com.test.banking.core.ledger.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

@Getter
@Setter
@Entity
@Table(name = "transactions", schema = "ledger")
public class TransactionEntity {

    @Id
    private String txnId;

    @Column(nullable = false)
    private String txnType;

    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private long amountPaise;

    @Column(nullable = false, length = 3, columnDefinition = "CHAR(3)")
    private String currency;

    @Column(nullable = false)
    private LocalDate postingDate;

    @Column(nullable = false)
    private LocalDate valueDate;

    private String narration;
    private String referenceNumber;
    private String idempotencyKey;
    private String requestFingerprint;

    @Column(nullable = false)
    private String initiatedBy;

    @Column(nullable = false)
    private Instant initiatedAt;

    private Instant postedAt;
    private String reversalOf;
    private String responseSnapshot;
}
