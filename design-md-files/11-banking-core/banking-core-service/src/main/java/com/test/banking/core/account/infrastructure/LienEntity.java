package com.test.banking.core.account.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "liens", schema = "accounts")
public class LienEntity {

    @Id
    private UUID lienId;

    @Column(nullable = false)
    private String accountId;

    @Column(nullable = false)
    private long amountPaise;

    @Column(nullable = false)
    private String reason;

    @Column(nullable = false)
    private String status;

    private String lienType;
    private String referenceId;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant expiresAt;
    private Instant releasedAt;
    private String releasedBy;
}
