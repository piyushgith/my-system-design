package com.homeloan.creditcheck.dto;

public final class KafkaTopicsConstant {

    public static final String LOAN_APPLICATION_EVENTS = "loan-application-events";
    public static final String CREDIT_CHECK_EVENTS = "credit-check-events";
    public static final String PROPERTY_VALUATION_EVENTS = "property-valuation-events";
    public static final String DOCUMENT_VERIFICATION_EVENTS = "document-verification-events";
    public static final String LOAN_PROCESSING_EVENTS = "loan-processing-events";
    public static final String NOTIFICATION_EVENTS = "notification-events";
    public static final String SAGA_ORCHESTRATION_EVENTS = "saga-orchestration-events";

    private KafkaTopicsConstant() {
    }

}
