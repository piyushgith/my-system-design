package com.homeloan.saga.orchestrator.dto;

public enum SagaStatus {
    STARTED, IN_PROGRESS, COMPLETED, FAILED, COMPENSATING, COMPENSATED
}
