package com.test.kyc.identification.vendor;

import lombok.Builder;
import lombok.Getter;

import java.util.Map;

@Getter
@Builder
public class OcrResult {
    private final boolean success;
    private final double confidenceScore;
    private final String extractedName;
    private final String extractedDob;
    private final String documentNumber;
    private final String expiryDate;
    private final String failureReason;
    private final Map<String, Object> rawFields;
}
