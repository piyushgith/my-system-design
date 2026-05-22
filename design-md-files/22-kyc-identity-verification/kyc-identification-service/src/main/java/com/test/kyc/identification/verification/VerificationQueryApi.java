package com.test.kyc.identification.verification;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface VerificationQueryApi {

    void startPipeline(UUID applicationId);

    List<StepSummary> getStepSummaries(UUID applicationId);

    record StepSummary(String stepType, String status, Instant completedAt) {}
}
