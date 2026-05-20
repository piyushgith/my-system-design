package com.test.kyc.identification.review.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "manual_review_queue")
@Getter
@Setter
@NoArgsConstructor
public class ManualReviewQueue {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "review_id", updatable = false, nullable = false)
    private UUID reviewId;

    @Column(name = "application_id", nullable = false, unique = true)
    private UUID applicationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 10)
    private ReviewPriority priority;

    @Column(name = "routing_reason", nullable = false, length = 50)
    private String routingReason;

    @Column(name = "assigned_reviewer")
    private UUID assignedReviewer;

    @Column(name = "assigned_at")
    private Instant assignedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "decision", length = 20)
    private String decision;

    @Column(name = "notes")
    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
    }

    public static ManualReviewQueue create(UUID applicationId, ReviewPriority priority, String routingReason) {
        var item = new ManualReviewQueue();
        item.applicationId = applicationId;
        item.priority = priority;
        item.routingReason = routingReason;
        return item;
    }
}
