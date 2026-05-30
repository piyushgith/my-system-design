package com.homeloan.creditcheck.dto;

public enum EventType {
    SAGA_STARTED,
    SAGA_STEP_COMPLETED,

    SAGA_STEP_FAILED,
    SAGA_COMPLETED,

    SAGA_COMPENSATION_STARTED,
    SAGA_COMPENSATION_COMPLETED
}
