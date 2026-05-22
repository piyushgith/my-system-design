package com.test.kyc.identification.verification.service;

import com.test.kyc.identification.application.domain.DocumentReference;
import com.test.kyc.identification.application.domain.KycApplication;
import com.test.kyc.identification.application.domain.KycStatus;
import com.test.kyc.identification.application.repository.DocumentReferenceRepository;
import com.test.kyc.identification.application.repository.KycApplicationRepository;
import com.test.kyc.identification.application.service.StateMachineService;
import com.test.kyc.identification.common.encryption.PiiEncryptionService;
import com.test.kyc.identification.notification.KycOutcomePublisher;
import com.test.kyc.identification.review.domain.ManualReviewQueue;
import com.test.kyc.identification.review.domain.ReviewPriority;
import com.test.kyc.identification.review.repository.ManualReviewQueueRepository;
import com.test.kyc.identification.vendor.LivenessResult;
import com.test.kyc.identification.vendor.OcrResult;
import com.test.kyc.identification.vendor.VendorClient;
import com.test.kyc.identification.vendor.WatchlistResult;
import com.test.kyc.identification.verification.domain.StepStatus;
import com.test.kyc.identification.verification.domain.StepType;
import com.test.kyc.identification.verification.domain.VerificationStep;
import com.test.kyc.identification.verification.repository.VerificationStepRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.test.kyc.identification.verification.VerificationQueryApi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class VerificationPipelineService implements VerificationQueryApi {

    private final VendorClient vendorClient;
    private final StateMachineService stateMachine;
    private final KycApplicationRepository applicationRepository;
    private final DocumentReferenceRepository documentReferenceRepository;
    private final VerificationStepRepository stepRepository;
    private final ManualReviewQueueRepository reviewQueueRepository;
    private final KycOutcomePublisher outcomePublisher;
    private final PiiEncryptionService encryptionService;
    private final ObjectMapper objectMapper;

    // Self-injection via @Lazy so Spring's proxy intercepts @Transactional on internal calls.
    @Lazy
    @Autowired
    private VerificationPipelineService self;

    @Override
    @Async
    public void startPipeline(UUID applicationId) {
        KycApplication application = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new IllegalStateException("Application not found: " + applicationId));
        log.info("Pipeline starting for application={}", applicationId);
        try {
            self.runDocumentOcr(application);
        } catch (Exception e) {
            log.error("Unhandled pipeline error for application={}", applicationId, e);
            self.routeToManualReview(application, "VENDOR_ERROR", ReviewPriority.LOW,
                    "Pipeline error: " + e.getMessage());
        }
    }

    @Transactional
    protected void runDocumentOcr(KycApplication application) {
        stateMachine.transition(application, KycStatus.DOCUMENT_VERIFICATION_PENDING,
                "SYSTEM", "kyc-pipeline", "Starting document OCR");

        VerificationStep step = VerificationStep.create(application.getApplicationId(), StepType.DOCUMENT_OCR);
        step.setVendor(vendorClient.vendorName());
        step.setStatus(StepStatus.IN_PROGRESS);
        step.setStartedAt(Instant.now());
        stepRepository.save(step);

        List<DocumentReference> docs = documentReferenceRepository
                .findByApplicationIdAndIsPurgedFalse(application.getApplicationId());

        DocumentReference primaryDoc = docs.stream()
                .filter(d -> !"SELFIE".equals(d.getDocumentType()))
                .findFirst()
                .orElse(docs.isEmpty() ? null : docs.get(0));

        if (primaryDoc == null) {
            failStep(step, "No documents found");
            self.routeToManualReview(application, "DOCUMENT_REJECTED", ReviewPriority.MEDIUM,
                    "No documents attached");
            return;
        }

        String s3Key = encryptionService.decryptS3Key(primaryDoc.getS3KeyEncrypted());
        OcrResult ocrResult = vendorClient.performDocumentOcr(s3Key, primaryDoc.getDocumentType());

        step.setResult(ocrResultToMap(ocrResult));

        if (ocrResult.isSuccess() && ocrResult.getConfidenceScore() >= 0.85) {
            step.setStatus(StepStatus.PASS);
            step.setCompletedAt(Instant.now());
            stepRepository.save(step);
            stateMachine.transition(application, KycStatus.DOCUMENT_VERIFIED,
                    "SYSTEM", "kyc-pipeline", "OCR passed, confidence=" + ocrResult.getConfidenceScore());
            self.runLivenessCheck(application);
        } else {
            failStep(step, ocrResult.getFailureReason());
            stateMachine.transition(application, KycStatus.DOCUMENT_REJECTED,
                    "SYSTEM", "kyc-pipeline",
                    "OCR failed: " + ocrResult.getFailureReason());
            self.routeToManualReview(application, "DOCUMENT_REJECTED", ReviewPriority.MEDIUM,
                    "Low OCR confidence: " + ocrResult.getConfidenceScore());
        }
    }

    @Transactional
    protected void runLivenessCheck(KycApplication application) {
        stateMachine.transition(application, KycStatus.LIVENESS_PENDING,
                "SYSTEM", "kyc-pipeline", "Starting liveness check");

        VerificationStep step = VerificationStep.create(application.getApplicationId(), StepType.LIVENESS);
        step.setVendor(vendorClient.vendorName());
        step.setStatus(StepStatus.IN_PROGRESS);
        step.setStartedAt(Instant.now());
        stepRepository.save(step);

        List<DocumentReference> docs = documentReferenceRepository
                .findByApplicationIdAndIsPurgedFalse(application.getApplicationId());

        DocumentReference selfie = docs.stream()
                .filter(d -> "SELFIE".equals(d.getDocumentType()))
                .findFirst()
                .orElse(null);

        if (selfie == null) {
            failStep(step, "No selfie document found");
            self.routeToManualReview(application, "LIVENESS_FAILED", ReviewPriority.MEDIUM,
                    "No selfie document attached");
            return;
        }

        String selfieKey = encryptionService.decryptS3Key(selfie.getS3KeyEncrypted());
        LivenessResult result = vendorClient.performLivenessCheck(selfieKey);

        step.setResult(Map.of(
                "live", result.isLive(),
                "confidence", result.getConfidenceScore()
        ));

        if (result.isLive()) {
            step.setStatus(StepStatus.PASS);
            step.setCompletedAt(Instant.now());
            stepRepository.save(step);
            stateMachine.transition(application, KycStatus.LIVENESS_PASSED,
                    "SYSTEM", "kyc-pipeline", "Liveness passed");
            self.runWatchlistScreening(application);
        } else {
            failStep(step, result.getFailureReason());
            stateMachine.transition(application, KycStatus.LIVENESS_FAILED,
                    "SYSTEM", "kyc-pipeline", "Liveness failed: " + result.getFailureReason());
            self.routeToManualReview(application, "LIVENESS_FAILED", ReviewPriority.MEDIUM,
                    "Liveness check failed");
        }
    }

    @Transactional
    protected void runWatchlistScreening(KycApplication application) {
        stateMachine.transition(application, KycStatus.WATCHLIST_SCREENING,
                "SYSTEM", "kyc-pipeline", "Starting watchlist screening");

        VerificationStep step = VerificationStep.create(application.getApplicationId(), StepType.WATCHLIST_SCREENING);
        step.setVendor(vendorClient.vendorName());
        step.setStatus(StepStatus.IN_PROGRESS);
        step.setStartedAt(Instant.now());
        stepRepository.save(step);

        String piiJson = encryptionService.decrypt(application.getPersonalDataEncrypted());
        String fullName = extractField(piiJson, "full_name");
        String dob = extractField(piiJson, "date_of_birth");
        String nationality = extractField(piiJson, "nationality");

        WatchlistResult result = vendorClient.performWatchlistScreening(fullName, dob, nationality);

        step.setResult(Map.of(
                "clear", result.isClear(),
                "hitCount", result.getHits().size()
        ));
        step.setCompletedAt(Instant.now());

        if (result.isClear()) {
            step.setStatus(StepStatus.PASS);
            step.setCompletedAt(Instant.now());
            stepRepository.save(step);
            stateMachine.transition(application, KycStatus.WATCHLIST_CLEAR,
                    "SYSTEM", "kyc-pipeline", "No watchlist hits");
            self.approveApplication(application);
        } else {
            failStep(step, "Watchlist hit: " + result.getHits().size() + " match(es)");
            stateMachine.transition(application, KycStatus.WATCHLIST_HIT,
                    "SYSTEM", "kyc-pipeline",
                    "Watchlist hit count=" + result.getHits().size());
            self.routeToManualReview(application, "WATCHLIST_HIT", ReviewPriority.HIGH,
                    "Sanctions/PEP match detected");
        }
    }

    @Transactional
    protected void approveApplication(KycApplication application) {
        stateMachine.transition(application, KycStatus.APPROVED,
                "SYSTEM", "kyc-pipeline", "All automated checks passed");
        outcomePublisher.publishOutcome(application.getApplicationId(), "APPROVED", null);
        log.info("Application APPROVED: {}", application.getApplicationId());
    }

    @Transactional
    protected void routeToManualReview(KycApplication application,
                                        String routingReason,
                                        ReviewPriority priority,
                                        String notes) {
        if (!application.getStatus().isTerminal()
                && application.getStatus() != KycStatus.MANUAL_REVIEW) {
            stateMachine.transition(application, KycStatus.MANUAL_REVIEW,
                    "SYSTEM", "kyc-pipeline", "Routed to manual review: " + routingReason);
        }

        boolean alreadyQueued = reviewQueueRepository.findByApplicationId(application.getApplicationId()).isPresent();
        if (!alreadyQueued) {
            reviewQueueRepository.save(
                    ManualReviewQueue.create(application.getApplicationId(), priority, routingReason));
        }

        outcomePublisher.publishManualReviewRequired(application.getApplicationId(), routingReason, priority.name());
        log.info("Application {} routed to MANUAL_REVIEW: reason={}", application.getApplicationId(), routingReason);
    }

    @Override
    public List<StepSummary> getStepSummaries(UUID applicationId) {
        return stepRepository.findByApplicationId(applicationId).stream()
                .map(s -> new StepSummary(
                        s.getStepType().name(), s.getStatus().name(), s.getCompletedAt()))
                .toList();
    }

    private void failStep(VerificationStep step, String reason) {
        step.setStatus(StepStatus.FAIL);
        step.setFailureReason(reason);
        step.setCompletedAt(Instant.now());
        stepRepository.save(step);
    }

    private Map<String, Object> ocrResultToMap(OcrResult r) {
        return Map.of(
                "success", r.isSuccess(),
                "confidence", r.getConfidenceScore(),
                "extractedName", r.getExtractedName() != null ? r.getExtractedName() : "",
                "documentNumber", r.getDocumentNumber() != null ? r.getDocumentNumber() : ""
        );
    }

    private String extractField(String json, String field) {
        try {
            Map<String, Object> map = objectMapper.readValue(json, new TypeReference<>() {});
            Object val = map.get(field);
            return val != null ? val.toString() : "";
        } catch (Exception e) {
            log.warn("Failed to extract field '{}' from PII JSON", field, e);
            return "";
        }
    }
}
