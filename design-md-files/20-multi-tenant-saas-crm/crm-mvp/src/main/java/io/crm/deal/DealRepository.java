package io.crm.deal;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

public interface DealRepository extends JpaRepository<Deal, UUID> {

    @Query(value = "SELECT d FROM Deal d " +
                   "LEFT JOIN FETCH d.pipeline " +
                   "LEFT JOIN FETCH d.stage " +
                   "LEFT JOIN FETCH d.owner " +
                   "LEFT JOIN FETCH d.contact " +
                   "WHERE d.deletedAt IS NULL " +
                   "AND (:status IS NULL OR d.status = :status) " +
                   "AND (:ownerId IS NULL OR d.owner.userId = :ownerId) " +
                   "AND (:pipelineId IS NULL OR d.pipeline.pipelineId = :pipelineId)",
           countQuery = "SELECT COUNT(d) FROM Deal d WHERE d.deletedAt IS NULL " +
                        "AND (:status IS NULL OR d.status = :status) " +
                        "AND (:ownerId IS NULL OR d.owner.userId = :ownerId) " +
                        "AND (:pipelineId IS NULL OR d.pipeline.pipelineId = :pipelineId)")
    Page<Deal> findActive(
            @Param("status") DealStatus status,
            @Param("ownerId") UUID ownerId,
            @Param("pipelineId") UUID pipelineId,
            Pageable pageable);

    @Query("SELECT d FROM Deal d WHERE d.dealId = :id AND d.deletedAt IS NULL")
    Optional<Deal> findActiveById(@Param("id") UUID id);

    long countByStatusAndDeletedAtIsNull(DealStatus status);

    @Query("SELECT COALESCE(SUM(d.value), 0) FROM Deal d WHERE d.status = io.crm.deal.DealStatus.OPEN AND d.deletedAt IS NULL")
    BigDecimal sumOpenPipelineValue();
}
