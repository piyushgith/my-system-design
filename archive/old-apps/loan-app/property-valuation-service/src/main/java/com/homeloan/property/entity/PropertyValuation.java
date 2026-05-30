package com.homeloan.property.entity;


import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;


@NoArgsConstructor
@AllArgsConstructor
@Builder
@Data
@Entity
@Table(name = "property_valuations")
public class PropertyValuation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "application_id", nullable = false)
    private Long applicationId;

    @Column(name = "property_address", nullable = false)
    private String propertyAddress;

    @Column(name = "saga_id", nullable = false)
    private String sagaId;

    @Column(name = "estimated_value", nullable = false, precision = 12, scale = 2)
    private BigDecimal estimatedValue;

    @Column(name = "valuation_date", nullable = false)
    private java.time.LocalDateTime valuationDate;

    @Column(name = "valuator_name", nullable = false)
    private String valuatorName;

    @Column(name = "remarks")
    private String remarks;

    @Enumerated(EnumType.STRING)
    @Column(name = "valuation_status", nullable = false)
    private ValuationStatus valuationStatus;

    @Column(name = "loan_to_value_ratio", precision = 5, scale = 2)
    private BigDecimal loanToValueRatio;

    public enum ValuationStatus {
        PENDING, APPROVED, REJECTED
    }

    @PrePersist
    protected void onCreate() {
        this.valuationDate = java.time.LocalDateTime.now();
        if (valuationStatus == null) {
            this.valuationStatus = ValuationStatus.PENDING;
        }
    }

}
