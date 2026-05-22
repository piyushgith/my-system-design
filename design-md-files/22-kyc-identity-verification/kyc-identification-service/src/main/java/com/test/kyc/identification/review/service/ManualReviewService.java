package com.test.kyc.identification.review.service;

import com.test.kyc.identification.application.domain.KycApplication;
import com.test.kyc.identification.application.domain.KycStatus;
import com.test.kyc.identification.application.repository.KycApplicationRepository;
import com.test.kyc.identification.application.service.StateMachineService;
import com.test.kyc.identification.common.exception.ApplicationNotFoundException;
import com.test.kyc.identification.common.exception.ReviewAlreadyDecidedException;
import com.test.kyc.identification.notification.KycOutcomePublisher;
import com.test.kyc.identification.review.domain.ManualReviewQueue;
import com.test.kyc.identification.review.domain.ReviewPriority;
import com.test.kyc.identification.review.repository.ManualReviewQueueRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ManualReviewService {

    private final ManualReviewQueueRepository reviewQueueRepository;
    private final KycApplicationRepository applicationRepository;
    private final StateMachineService stateMachine;
    private final KycOutcomePublisher outcomePublisher;

    public List<ManualReviewQueue> getPendingQueue(ReviewPriority priority, UUID assignedTo, int limit) {
        return reviewQueueRepository.findPendingByFilters(priority, assignedTo, PageRequest.of(0, limit));
    }

    @Transactional
    public ManualReviewQueue assignReviewer(UUID applicationId, UUID reviewerId) {
        ManualReviewQueue item = reviewQueueRepository.findByApplicationId(applicationId)
                .orElseThrow(() -> new ApplicationNotFoundException(applicationId));

        if (item.getCompletedAt() != null) {
            throw new ReviewAlreadyDecidedException(applicationId);
        }

        item.setAssignedReviewer(reviewerId);
        item.setAssignedAt(Instant.now());
        return reviewQueueRepository.save(item);
    }

    @Transactional
    public KycApplication submitDecision(UUID applicationId,
                                          UUID reviewerId,
                                          String decision,
                                          String notes) {
        ManualReviewQueue item = reviewQueueRepository.findByApplicationId(applicationId)
                .orElseThrow(() -> new ApplicationNotFoundException(applicationId));

        if (item.getCompletedAt() != null) {
            throw new ReviewAlreadyDecidedException(applicationId);
        }

        KycApplication application = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new ApplicationNotFoundException(applicationId));

        if (!"APPROVED".equals(decision) && !"REJECTED".equals(decision)) {
            throw new IllegalArgumentException("Decision must be APPROVED or REJECTED, got: " + decision);
        }
        KycStatus targetStatus = "APPROVED".equals(decision) ? KycStatus.APPROVED : KycStatus.REJECTED;

        stateMachine.transition(application, targetStatus,
                "OPERATOR", reviewerId.toString(),
                "Manual review decision: " + decision + (notes != null ? " | " + notes : ""));

        if (targetStatus == KycStatus.REJECTED) {
            application.setRejectionReason("MANUAL_REVIEW_REJECTED");
        }
        applicationRepository.save(application);

        item.setDecision(decision);
        item.setNotes(notes);
        item.setCompletedAt(Instant.now());
        item.setAssignedReviewer(reviewerId);
        reviewQueueRepository.save(item);

        outcomePublisher.publishOutcome(applicationId, decision,
                targetStatus == KycStatus.REJECTED ? "MANUAL_REVIEW_REJECTED" : null);

        log.info("Manual review decision: app={} decision={} reviewer={}", applicationId, decision, reviewerId);
        return application;
    }

    public ManualReviewQueue getReviewItem(UUID applicationId) {
        return reviewQueueRepository.findByApplicationId(applicationId)
                .orElseThrow(() -> new ApplicationNotFoundException(applicationId));
    }
}
