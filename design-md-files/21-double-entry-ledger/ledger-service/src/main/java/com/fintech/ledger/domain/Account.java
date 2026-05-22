package com.fintech.ledger.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "accounts")
@Getter
@Setter
@NoArgsConstructor
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "account_id")
    private UUID accountId;

    @Column(name = "account_code", nullable = false, unique = true)
    private String accountCode;

    @Column(name = "account_name", nullable = false)
    private String accountName;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false)
    private AccountType accountType;

    @Enumerated(EnumType.STRING)
    @Column(name = "normal_balance", nullable = false)
    private Direction normalBalance;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "owner_id")
    private UUID ownerId;

    @Column(name = "owner_type")
    private String ownerType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private AccountStatus status = AccountStatus.ACTIVE;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "closed_at")
    private Instant closedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata")
    private Map<String, String> metadata;

    public static Account create(String code, String name, AccountType type, String currency,
                                  UUID ownerId, String ownerType) {
        Account a = new Account();
        a.accountCode = code;
        a.accountName = name;
        a.accountType = type;
        a.normalBalance = type.normalBalance();
        a.currency = currency;
        a.ownerId = ownerId;
        a.ownerType = ownerType;
        return a;
    }

    public void freeze() {
        this.status = AccountStatus.FROZEN;
    }

    public void close() {
        this.status = AccountStatus.CLOSED;
        this.closedAt = Instant.now();
    }

    public boolean isActive() {
        return this.status.acceptsPostings();
    }
}
