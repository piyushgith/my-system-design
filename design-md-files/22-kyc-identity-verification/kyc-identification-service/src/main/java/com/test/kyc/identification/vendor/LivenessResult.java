package com.test.kyc.identification.vendor;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class LivenessResult {
    private final boolean live;
    private final double confidenceScore;
    private final String failureReason;
}
