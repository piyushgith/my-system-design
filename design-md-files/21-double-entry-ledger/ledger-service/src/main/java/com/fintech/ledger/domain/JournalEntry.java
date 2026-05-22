package com.fintech.ledger.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "journal_entries")
@Getter
@Setter
@NoArgsConstructor
public class JournalEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "entry_id")
    private UUID entryId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "posting_id", nullable = false)
    private Posting posting;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false)
    private Direction direction;

    // Stored in smallest currency unit (cents, paise) — no floating point
    @Column(name = "amount", nullable = false)
    private Long amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "effective_at", nullable = false)
    private Instant effectiveAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "description")
    private String description;

    @Column(name = "sequence_num", insertable = false, updatable = false)
    private Long sequenceNum;

    public static JournalEntry of(Posting posting, UUID accountId, Direction direction,
                                   Long amount, String currency, Instant effectiveAt, String description) {
        JournalEntry e = new JournalEntry();
        e.posting = posting;
        e.accountId = accountId;
        e.direction = direction;
        e.amount = amount;
        e.currency = currency;
        e.effectiveAt = effectiveAt;
        e.createdAt = Instant.now();
        e.description = description;
        return e;
    }
}
