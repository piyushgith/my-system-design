package com.test.banking.core.account.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

@Getter
@Setter
@Entity
@Table(name = "accounts", schema = "accounts")
public class AccountEntity {

    @Id
    private String accountId;

    @Column(nullable = false)
    private String cifId;

    @Column(nullable = false)
    private String accountType;

    @Column(nullable = false)
    private String productCode;

    @Column(nullable = false)
    private String status;

    @Column(nullable = false, length = 3, columnDefinition = "CHAR(3)")
    private String currency;

    @Column(nullable = false)
    private long currentBalancePaise;

    @Column(nullable = false)
    private long availableBalancePaise;

    @Column(nullable = false)
    private long overdraftLimitPaise;

    @Column(nullable = false)
    private LocalDate openDate;

    private LocalDate closureDate;
    private LocalDate lastTxnDate;
    private LocalDate dormancyDate;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;
}
