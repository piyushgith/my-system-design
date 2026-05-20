package com.homeloan.saga.orchestrator.entity;

public enum StepStatus {
    PENDING("Pending"),
    IN_PROGRESS("In Progress"),
    COMPLETED("Completed"),
    FAILED("Failed"),
    COMPENSATING("Compensating"),
    COMPENSATED("Compensated");

    private String displayName;

    StepStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
