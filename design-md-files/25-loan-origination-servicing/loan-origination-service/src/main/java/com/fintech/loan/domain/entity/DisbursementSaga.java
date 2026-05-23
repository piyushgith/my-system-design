package com.fintech.loan.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "disbursement_sagas")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DisbursementSaga {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "saga_id")
    private UUID sagaId;

    @Column(name = "offer_id", nullable = false)
    private UUID offerId;

    @Column(name = "status", nullable = false, length = 32)
    @Builder.Default
    private String status = "INITIATED";

    @Column(name = "ledger_reserved_at")
    private Instant ledgerReservedAt;

    @Column(name = "bank_transfer_ref", length = 128)
    private String bankTransferRef;

    @Column(name = "bank_transfer_at")
    private Instant bankTransferAt;

    @Column(name = "bank_confirmed_at")
    private Instant bankConfirmedAt;

    @Column(name = "loan_activated_at")
    private Instant loanActivatedAt;

    @Column(name = "compensated_at")
    private Instant compensatedAt;

    @Column(name = "failure_reason", columnDefinition = "text")
    private String failureReason;

    @Column(name = "idempotency_key", unique = true, nullable = false, length = 128)
    private String idempotencyKey;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
