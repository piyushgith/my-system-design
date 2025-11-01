package com.homeloan.saga.orchestrator.repository;

import com.homeloan.saga.orchestrator.dto.SagaStatus;
import com.homeloan.saga.orchestrator.entity.SagaTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;


@Repository
public interface SagaTransactionRepository extends JpaRepository<SagaTransaction,Long> {

    // Find by saga ID
    Optional<SagaTransaction> findBySagaId(String sagaId);

    // Find by loan application ID
    Optional<SagaTransaction> findByLoanApplicationId(Long loanApplicationId);

    // Check if saga exists for loan application
    boolean existsByLoanApplicationId(Long loanApplicationId);

    // Find all sagas by status
    List<SagaTransaction> findBySagaStatus(SagaStatus sagaStatus);

    // Find sagas by multiple statuses
    List<SagaTransaction> findBySagaStatusIn(List<SagaStatus> statuses);

    // Find active (non-terminal) sagas
    @Query("SELECT st FROM SagaTransaction st WHERE st.sagaStatus NOT IN ('COMPLETED', 'FAILED', 'COMPENSATED')")
    List<SagaTransaction> findAllActiveSagas();

    // Find sagas requiring compensation
    List<SagaTransaction> findByCompensationRequiredTrue();

    // Find sagas by status and compensation requirement
    List<SagaTransaction> findBySagaStatusAndCompensationRequired(SagaStatus sagaStatus, Boolean compensationRequired);

    // Find sagas started within time range
    List<SagaTransaction> findByStartedAtBetween(LocalDateTime start, LocalDateTime end);

    // Find sagas updated after specific time
    List<SagaTransaction> findByUpdatedAtAfter(LocalDateTime dateTime);

    // Find stuck/stale sagas (updated more than X minutes ago and not terminal)
    @Query("SELECT st FROM SagaTransaction st WHERE st.updatedAt < :staleThreshold " +
            "AND st.sagaStatus NOT IN ('COMPLETED', 'FAILED', 'COMPENSATED')")
    List<SagaTransaction> findStaleSagas(@Param("staleThreshold") LocalDateTime staleThreshold);

    // Find failed sagas
    List<SagaTransaction> findBySagaStatusOrderByUpdatedAtDesc(SagaStatus sagaStatus);

    // Find sagas by current step
    List<SagaTransaction> findByCurrentStep(String currentStep);

    // Count sagas by status
    long countBySagaStatus(SagaStatus sagaStatus);

    // Count active sagas
    @Query("SELECT COUNT(st) FROM SagaTransaction st WHERE st.sagaStatus NOT IN ('COMPLETED', 'FAILED', 'COMPENSATED')")
    long countActiveSagas();

    // Get saga statistics by status
    @Query("SELECT st.sagaStatus, COUNT(st) FROM SagaTransaction st GROUP BY st.sagaStatus")
    List<Object[]> getSagaStatisticsByStatus();

    // Find sagas with low completion rate
    @Query("SELECT st FROM SagaTransaction st WHERE (st.completedSteps * 100.0 / st.totalSteps) < :minPercentage " +
            "AND st.sagaStatus = 'IN_PROGRESS'")
    List<SagaTransaction> findSagasWithLowProgress(@Param("minPercentage") double minPercentage);

    // Find long-running sagas
    @Query("SELECT st FROM SagaTransaction st WHERE st.startedAt < :threshold " +
            "AND st.sagaStatus NOT IN ('COMPLETED', 'FAILED', 'COMPENSATED')")
    List<SagaTransaction> findLongRunningSagas(@Param("threshold") LocalDateTime threshold);

    // Update saga status
    @Modifying
    @Query("UPDATE SagaTransaction st SET st.sagaStatus = :status, st.updatedAt = :updatedAt " +
            "WHERE st.sagaId = :sagaId")
    int updateSagaStatus(@Param("sagaId") String sagaId,
                         @Param("status") SagaStatus status,
                         @Param("updatedAt") LocalDateTime updatedAt);

    // Update compensation requirement
    @Modifying
    @Query("UPDATE SagaTransaction st SET st.compensationRequired = :required, st.updatedAt = :updatedAt " +
            "WHERE st.sagaId = :sagaId")
    int updateCompensationRequired(@Param("sagaId") String sagaId,
                                   @Param("required") Boolean required,
                                   @Param("updatedAt") LocalDateTime updatedAt);

    // Update current step and completed steps count
    @Modifying
    @Query("UPDATE SagaTransaction st SET st.currentStep = :currentStep, " +
            "st.completedSteps = :completedSteps, st.updatedAt = :updatedAt " +
            "WHERE st.sagaId = :sagaId")
    int updateCurrentStep(@Param("sagaId") String sagaId,
                          @Param("currentStep") String currentStep,
                          @Param("completedSteps") Integer completedSteps,
                          @Param("updatedAt") LocalDateTime updatedAt);

    // Find sagas completed within last N hours
    @Query("SELECT st FROM SagaTransaction st WHERE st.sagaStatus = 'COMPLETED' " +
            "AND st.completedAt > :since")
    List<SagaTransaction> findRecentlyCompletedSagas(@Param("since") LocalDateTime since);

    // Find sagas by status with pagination support (use in Service with Pageable)
    List<SagaTransaction> findTop10BySagaStatusOrderByUpdatedAtDesc(SagaStatus sagaStatus);

    // Delete old completed sagas (for cleanup)
    @Modifying
    @Query("DELETE FROM SagaTransaction st WHERE st.sagaStatus IN ('COMPLETED', 'COMPENSATED') " +
            "AND st.completedAt < :threshold")
    int deleteOldCompletedSagas(@Param("threshold") LocalDateTime threshold);

    // Find sagas by error pattern
    @Query("SELECT st FROM SagaTransaction st WHERE st.errorMessage LIKE %:errorPattern%")
    List<SagaTransaction> findByErrorMessageContaining(@Param("errorPattern") String errorPattern);

    // Get average completion time for completed sagas
    @Query("SELECT AVG(FUNCTION('TIMESTAMPDIFF', MINUTE, st.startedAt, st.completedAt)) " +
            "FROM SagaTransaction st WHERE st.sagaStatus = 'COMPLETED' AND st.startedAt IS NOT NULL")
    Double getAverageCompletionTimeInMinutes();

    // Find sagas with specific step count
    List<SagaTransaction> findByTotalSteps(Integer totalSteps);

    // Find failed sagas that need manual intervention
    @Query("SELECT st FROM SagaTransaction st WHERE st.sagaStatus = 'FAILED' " +
            "AND st.compensationRequired = false AND st.updatedAt < :threshold")
    List<SagaTransaction> findFailedSagasNeedingIntervention(@Param("threshold") LocalDateTime threshold);



}
