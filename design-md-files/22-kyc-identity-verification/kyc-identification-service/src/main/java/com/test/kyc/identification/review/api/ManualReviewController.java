package com.test.kyc.identification.review.api;

import com.test.kyc.identification.application.api.dto.ApplicationStatusResponse;
import com.test.kyc.identification.review.api.dto.ReviewDecisionRequest;
import com.test.kyc.identification.review.api.dto.ReviewQueueItemResponse;
import com.test.kyc.identification.review.domain.ReviewPriority;
import com.test.kyc.identification.review.service.ManualReviewService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/kyc/review")
@RequiredArgsConstructor
public class ManualReviewController {

    private final ManualReviewService reviewService;

    /** List pending review queue. No PII exposed — IDs and metadata only. */
    @GetMapping("/queue")
    public ResponseEntity<ReviewQueueItemResponse.PagedResponse> getQueue(
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) UUID assignedTo,
            @RequestParam(defaultValue = "20") int limit) {

        ReviewPriority priorityEnum = priority != null ? ReviewPriority.valueOf(priority) : null;
        var items = reviewService.getPendingQueue(priorityEnum, assignedTo, limit)
                .stream()
                .map(ReviewQueueItemResponse::from)
                .toList();

        return ResponseEntity.ok(new ReviewQueueItemResponse.PagedResponse(items, items.size()));
    }

    /** Get single review item. */
    @GetMapping("/{applicationId}")
    public ResponseEntity<ReviewQueueItemResponse> getReviewItem(
            @PathVariable UUID applicationId) {

        return ResponseEntity.ok(
                ReviewQueueItemResponse.from(reviewService.getReviewItem(applicationId)));
    }

    /** Assign application to a reviewer. */
    @PostMapping("/{applicationId}/assign")
    public ResponseEntity<ReviewQueueItemResponse> assign(
            @PathVariable UUID applicationId,
            @RequestParam UUID reviewerId) {

        return ResponseEntity.ok(
                ReviewQueueItemResponse.from(reviewService.assignReviewer(applicationId, reviewerId)));
    }

    /** Submit a final review decision — APPROVED or REJECTED. */
    @PostMapping("/{applicationId}/decision")
    public ResponseEntity<ApplicationStatusResponse> submitDecision(
            @PathVariable UUID applicationId,
            @Valid @RequestBody ReviewDecisionRequest request) {

        var application = reviewService.submitDecision(
                applicationId,
                request.reviewerId(),
                request.decision(),
                request.notes()
        );

        return ResponseEntity.ok(ApplicationStatusResponse.from(application));
    }
}
