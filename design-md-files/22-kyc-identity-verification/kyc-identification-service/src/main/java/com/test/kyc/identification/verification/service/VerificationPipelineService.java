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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class VerificationPipelineService {

    private final VendorClient vendorClient;
    private final StateMachineService stateMachine;
    private final KycApplicationRepository applicationRepository;
    private final DocumentReferenceRepository documentReferenceRepository;
    private final VerificationStepRepository stepRepository;
    private final ManualReviewQueueRepository reviewQueueRepository;
    private final KycOutcomePublisher outcomePublisher;
    private final PiiEncryptionService encryptionService;

    /**
     * Entry point — called after application is persisted.
     * Runs async so the submission API returns 202 immediately.
     */
    @Async
    public void startPipeline(KycApplication application) {
        log.info("Pipeline starting for application={}", application.getApplicationId());
        try {
            runDocumentOcr(application);
        } catch (Exception e) {
            log.error("Unhandled pipeline error for application={}", application.getApplicationId(), e);
            routeToManualReview(application, "VENDOR_ERROR", ReviewPriority.LOW,
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
            routeToManualReview(application, "DOCUMENT_REJECTED", ReviewPriority.MEDIUM,
                    "No documents attached");
            return;
        }

        String s3Key = encryptionService.decryptS3Key(primaryDoc.getS3KeyEncrypted());
        OcrResult ocrResult = vendorClient.performDocumentOcr(s3Key, primaryDoc.getDocumentType());

        step.setResult(ocrResultToMap(ocrResult));
        step.setCompletedAt(Instant.now());

        if (ocrResult.isSuccess() && ocrResult.getConfidenceScore() >= 0.85) {
            step.setStatus(StepStatus.PASS);
            stepRepository.save(step);
            stateMachine.transition(application, KycStatus.DOCUMENT_VERIFIED,
                    "SYSTEM", "kyc-pipeline", "OCR passed, confidence=" + ocrResult.getConfidenceScore());
            runLivenessCheck(application);
        } else {
            failStep(step, ocrResult.getFailureReason());
            stateMachine.transition(application, KycStatus.DOCUMENT_REJECTED,
                    "SYSTEM", "kyc-pipeline",
                    "OCR failed: " + ocrResult.getFailureReason());
            routeToManualReview(application, "DOCUMENT_REJECTED", ReviewPriority.MEDIUM,
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

        String selfieKey = selfie != null
                ? encryptionService.decryptS3Key(selfie.getS3KeyEncrypted())
                : "no-selfie";

        LivenessResult result = vendorClient.performLivenessCheck(selfieKey);

        step.setResult(Map.of(
                "live", result.isLive(),
                "confidence", result.getConfidenceScore()
        ));
        step.setCompletedAt(Instant.now());

        if (result.isLive()) {
            step.setStatus(StepStatus.PASS);
            stepRepository.save(step);
            stateMachine.transition(application, KycStatus.LIVENESS_PASSED,
                    "SYSTEM", "kyc-pipeline", "Liveness passed");
            runWatchlistScreening(application);
        } else {
            failStep(step, result.getFailureReason());
            stateMachine.transition(application, KycStatus.LIVENESS_FAILED,
                    "SYSTEM", "kyc-pipeline", "Liveness failed: " + result.getFailureReason());
            routeToManualReview(application, "LIVENESS_FAILED", ReviewPriority.MEDIUM,
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
            stepRepository.save(step);
            stateMachine.transition(application, KycStatus.WATCHLIST_CLEAR,
                    "SYSTEM", "kyc-pipeline", "No watchlist hits");
            approveApplication(application);
        } else {
            failStep(step, "Watchlist hit: " + result.getHits().size() + " match(es)");
            stateMachine.transition(application, KycStatus.WATCHLIST_HIT,
                    "SYSTEM", "kyc-pipeline",
                    "Watchlist hit count=" + result.getHits().size());
            routeToManualReview(application, "WATCHLIST_HIT", ReviewPriority.HIGH,
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

    /** Minimal JSON field extraction without pulling in Jackson dependency here. */
    private String extractField(String json, String field) {
        String key = "\"" + field + "\"";
        int idx = json.indexOf(key);
        if (idx < 0) return "";
        int colon = json.indexOf(':', idx);
        if (colon < 0) return "";
        int valStart = json.indexOf('"', colon) + 1;
        int valEnd = json.indexOf('"', valStart);
        if (valStart <= 0 || valEnd < 0) return "";
        return json.substring(valStart, valEnd);
    }
}
