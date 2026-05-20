package com.test.kyc.identification.review.api.dto;

import com.test.kyc.identification.review.domain.ManualReviewQueue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReviewQueueItemResponse(
        UUID reviewId,
        UUID applicationId,
        String priority,
        String routingReason,
        UUID assignedReviewer,
        long ageMinutes,
        Instant createdAt
) {
    public static ReviewQueueItemResponse from(ManualReviewQueue item) {
        return new ReviewQueueItemResponse(
                item.getReviewId(),
                item.getApplicationId(),
                item.getPriority().name(),
                item.getRoutingReason(),
                item.getAssignedReviewer(),
                Duration.between(item.getCreatedAt(), Instant.now()).toMinutes(),
                item.getCreatedAt()
        );
    }

    public record PagedResponse(List<ReviewQueueItemResponse> items, int total) {}
}
