package com.test.banking.core.account.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "open_idempotency", schema = "accounts")
public class OpenIdempotencyEntity {

    @Id
    private String idempotencyKey;

    @Column(nullable = false)
    private String accountId;

    @Column(nullable = false)
    private Instant createdAt;

    private String requestFingerprint;
}
