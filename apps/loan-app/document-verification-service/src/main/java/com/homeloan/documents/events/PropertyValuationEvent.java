package com.homeloan.documents.events;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PropertyValuationEvent {
    private Long applicationId;
    private String propertyAddress;
    private BigDecimal estimatedValue;
    private String valuationStatus;
    private LocalDateTime valuationDate;
    private String valuerName;
    private String sagaId;
    private String eventType;
    private LocalDateTime eventTime;

    public enum ValuationStatus {
        PENDING, APPROVED, REJECTED;
    }

    public enum EventType {
        PROPERTY_VALUATION_STARTED, PROPERTY_VALUATION_COMPLETED, PROPERTY_VALUATION_FAILED, PROPERTY_VALUATION_COMPENSATION;
    }


}
