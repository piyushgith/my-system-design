package com.homeloan.application.events;


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
public class LoanApplicationEvent {

    private Long applicationId;
    private String applicantName;
    private String applicantEmail;
    private String applicantPhone;
    private BigDecimal loanAmount;
    private String propertyAddress;
    private String applicationStatus;
    private String eventType;
    private LocalDateTime eventTime;
    private String sagaId;

    public enum EventType {
        LOAN_APPLICATION_CREATED,
        LOAN_APPLICATION_UPDATED,
        LOAN_APPLICATION_CANCELLED,
        CREDIT_CHECK_REQUESTED,
        CREDIT_CHECK_COMPLETED,
        PROPERTY_VALUATION_REQUESTED,
        PROPERTY_VALUATION_COMPLETED,
        DOCUMENT_VERIFICATION_REQUESTED,
        DOCUMENT_VERIFICATION_COMPLETED,
        LOAN_PROCESSING_REQUESTED,
        LOAN_PROCESSING_COMPLETED,
        NOTIFICATION_SENT,
        SAGA_STARTED,
        SAGA_COMPLETED,
        SAGA_COMPENSATED;
    }
}
