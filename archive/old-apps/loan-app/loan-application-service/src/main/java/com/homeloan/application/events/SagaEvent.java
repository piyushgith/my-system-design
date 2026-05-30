package com.homeloan.creditcheck.events;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SagaEvent {

    private String sagaId;
    private Long applicationId;
    private String sagaStatus;
    private String currentStep;
    private String eventType;
    private LocalDateTime eventTime;
    private String errorMessage;
    private boolean compensationRequired;
}


