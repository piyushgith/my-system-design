package com.homeloan.creditcheck.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "credit_checks")
public class CreditCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    @Column(name = "application_id", nullable = false)
    private Long applicationId;

    @Column(name = "credit_score", nullable = false)
    private Integer creditScore;

    @Enumerated(EnumType.STRING)
    @Column(name = "credit_status", nullable = false)
    private CreditStatus creditStatus;

    @Column(name = "check_date", nullable = false)
    private LocalDateTime checkDate;

    @Column(name = "remarks", nullable = false)
    private String remarks;

    @Column(name = "saga_id", nullable = false)
    private String sagaId;

    @PrePersist
    protected void onCreate() {
        checkDate = LocalDateTime.now();
        if (creditStatus == null) creditStatus = CreditStatus.PENDING;
    }
}
