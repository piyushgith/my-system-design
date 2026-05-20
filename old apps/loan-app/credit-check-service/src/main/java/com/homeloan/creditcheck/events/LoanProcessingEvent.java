package com.homeloan.creditcheck.events;

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
public class LoanProcessingEvent {

    private Long applicationId;
    private String approvalStatus;
    private BigDecimal interestRate;
    private Integer loanTermMonths;
    private BigDecimal monthlyPayment;
    private BigDecimal approvedAmount;
    private LocalDateTime processedDate;
    private String processedBy;
    private String sagaId;
    private String eventType;
    private LocalDateTime eventTime;

    public enum ApprovalStatus {
        PENDING, APPROVED, REJECTED
    }

    public enum EventType {
        LOAN_PROCESSING_STARTED, LOAN_PROCESSING_COMPLETED, LOAN_PROCESSING_FAILED, LOAN_PROCESSING_COMPENSATED
    }

}
