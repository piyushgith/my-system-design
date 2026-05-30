package com.homeloan.saga.orchestrator.events;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;


@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreditCheckEvent {
    private Long applicationId;
    private Integer creditScore;
    private String creditStatus;
    private String remarks;
    private LocalDateTime checkDate;
    private String sagaId;
    private String eventType;
    private LocalDateTime eventTime;

    public enum CreditStatus {
        PENDING, APPROVED, REJECTED
    }

    public enum EventType {
        CREDIT_CHECK_STARTED, CREDIT_CHECK_COMPLETED, CREDIT_CHECK_FAILED, CREDIT_CHECK_COMPENSATED
    }


}
