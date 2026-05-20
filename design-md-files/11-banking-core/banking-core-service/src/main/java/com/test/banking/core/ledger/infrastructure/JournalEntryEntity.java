package com.test.banking.core.ledger.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "journal_entries", schema = "ledger")
public class JournalEntryEntity {

    @Id
    private UUID entryId;

    @Column(nullable = false)
    private String txnId;

    private String accountId;

    @Column(nullable = false)
    private String glCode;

    @Column(nullable = false, length = 1, columnDefinition = "CHAR(1)")
    private String entryType;

    @Column(nullable = false)
    private long amountPaise;

    @Column(nullable = false, length = 3, columnDefinition = "CHAR(3)")
    private String currency;

    @Column(nullable = false)
    private LocalDate valueDate;

    @Column(nullable = false)
    private LocalDate postingDate;

    @Column(nullable = false)
    private Instant postedAt;

    private String narration;
}
