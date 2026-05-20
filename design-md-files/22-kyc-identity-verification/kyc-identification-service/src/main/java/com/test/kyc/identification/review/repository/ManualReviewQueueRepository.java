package com.test.kyc.identification.review.repository;

import com.test.kyc.identification.review.domain.ManualReviewQueue;
import com.test.kyc.identification.review.domain.ReviewPriority;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ManualReviewQueueRepository extends JpaRepository<ManualReviewQueue, UUID> {

    Optional<ManualReviewQueue> findByApplicationId(UUID applicationId);

    @Query("""
            SELECT q FROM ManualReviewQueue q
            WHERE q.assignedReviewer IS NULL AND q.completedAt IS NULL
            ORDER BY q.priority DESC, q.createdAt ASC
            """)
    List<ManualReviewQueue> findUnassignedByPriority(Pageable pageable);

    @Query("""
            SELECT q FROM ManualReviewQueue q
            WHERE (:priority IS NULL OR q.priority = :priority)
              AND (:assignedTo IS NULL OR q.assignedReviewer = :assignedTo)
              AND q.completedAt IS NULL
            ORDER BY q.priority DESC, q.createdAt ASC
            """)
    List<ManualReviewQueue> findPendingByFilters(@Param("priority") ReviewPriority priority,
                                                  @Param("assignedTo") UUID assignedTo,
                                                  Pageable pageable);
}
