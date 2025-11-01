package com.homeloan.saga.orchestrator.repository;


import com.homeloan.saga.orchestrator.entity.SagaStep;
import com.homeloan.saga.orchestrator.entity.SagaTransaction;
import com.homeloan.saga.orchestrator.entity.StepStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface SagaStepRepository extends JpaRepository<SagaStep, Long> {

    // Find all steps for a saga transaction
    List<SagaStep> findBySagaTransactionOrderByStepOrderAsc(SagaTransaction sagaTransaction);

    // Find steps by saga transaction ID
    @Query("SELECT ss FROM SagaStep ss WHERE ss.sagaTransaction.id = :sagaTransactionId ORDER BY ss.stepOrder ASC")
    List<SagaStep> findBySagaTransactionId(@Param("sagaTransactionId") Long sagaTransactionId);

    // Find steps by saga ID
    @Query("SELECT ss FROM SagaStep ss WHERE ss.sagaTransaction.sagaId = :sagaId ORDER BY ss.stepOrder ASC")
    List<SagaStep> findBySagaId(@Param("sagaId") String sagaId);

    // Find specific step by saga transaction and step name
    Optional<SagaStep> findBySagaTransactionAndStepName(SagaTransaction sagaTransaction, String stepName);

    // Find step by saga transaction and step order
    Optional<SagaStep> findBySagaTransactionAndStepOrder(SagaTransaction sagaTransaction, Integer stepOrder);

    // Find all steps with specific status
    List<SagaStep> findByStepStatus(StepStatus stepStatus);

    // Find steps by status for a specific saga
    List<SagaStep> findBySagaTransactionAndStepStatus(SagaTransaction sagaTransaction, StepStatus stepStatus);

    // Find failed steps that can be retried
    @Query("SELECT ss FROM SagaStep ss WHERE ss.stepStatus = 'FAILED' AND ss.retryCount < ss.maxRetries")
    List<SagaStep> findRetryableSteps();

    // Find failed steps for a specific saga
    @Query("SELECT ss FROM SagaStep ss WHERE ss.sagaTransaction.sagaId = :sagaId AND ss.stepStatus = 'FAILED'")
    List<SagaStep> findFailedStepsBySagaId(@Param("sagaId") String sagaId);

    // Find steps requiring compensation
    @Query("SELECT ss FROM SagaStep ss WHERE ss.stepStatus = 'COMPLETED' " +
            "AND ss.compensationExecuted = false " +
            "AND ss.sagaTransaction.compensationRequired = true")
    List<SagaStep> findStepsRequiringCompensation();

    // Find compensated steps
    List<SagaStep> findByCompensationExecutedTrue();

    // Find steps by service name
    List<SagaStep> findByServiceName(String serviceName);

    // Find steps by service name and status
    List<SagaStep> findByServiceNameAndStepStatus(String serviceName, StepStatus stepStatus);

    // Count steps by status for a saga
    @Query("SELECT COUNT(ss) FROM SagaStep ss WHERE ss.sagaTransaction.sagaId = :sagaId AND ss.stepStatus = :status")
    long countStepsByStatusForSaga(@Param("sagaId") String sagaId, @Param("status") StepStatus status);

    // Find in-progress steps older than threshold (stuck steps)
    @Query("SELECT ss FROM SagaStep ss WHERE ss.stepStatus = 'IN_PROGRESS' AND ss.updatedAt < :threshold")
    List<SagaStep> findStuckSteps(@Param("threshold") LocalDateTime threshold);

    // Find steps with high retry count
    @Query("SELECT ss FROM SagaStep ss WHERE ss.retryCount >= :minRetries")
    List<SagaStep> findStepsWithHighRetryCount(@Param("minRetries") Integer minRetries);

    // Find the next step to execute for a saga
    @Query("SELECT ss FROM SagaStep ss WHERE ss.sagaTransaction.sagaId = :sagaId " +
            "AND ss.stepStatus = 'PENDING' ORDER BY ss.stepOrder ASC LIMIT 1")
    Optional<SagaStep> findNextPendingStep(@Param("sagaId") String sagaId);

    // Find the current executing step
    @Query("SELECT ss FROM SagaStep ss WHERE ss.sagaTransaction.sagaId = :sagaId " +
            "AND ss.stepStatus = 'IN_PROGRESS'")
    Optional<SagaStep> findCurrentExecutingStep(@Param("sagaId") String sagaId);

    // Find completed steps for a saga ordered by completion time
    @Query("SELECT ss FROM SagaStep ss WHERE ss.sagaTransaction.sagaId = :sagaId " +
            "AND ss.stepStatus = 'COMPLETED' ORDER BY ss.completedAt DESC")
    List<SagaStep> findCompletedStepsBySagaId(@Param("sagaId") String sagaId);

    // Update step status
    @Modifying
    @Query("UPDATE SagaStep ss SET ss.stepStatus = :status, ss.updatedAt = :updatedAt " +
            "WHERE ss.id = :stepId")
    int updateStepStatus(@Param("stepId") Long stepId,
                         @Param("status") StepStatus status,
                         @Param("updatedAt") LocalDateTime updatedAt);

    // Increment retry count
    @Modifying
    @Query("UPDATE SagaStep ss SET ss.retryCount = ss.retryCount + 1, ss.updatedAt = :updatedAt " +
            "WHERE ss.id = :stepId")
    int incrementRetryCount(@Param("stepId") Long stepId, @Param("updatedAt") LocalDateTime updatedAt);

    // Mark step as compensated
    @Modifying
    @Query("UPDATE SagaStep ss SET ss.compensationExecuted = true, ss.stepStatus = 'COMPENSATED', " +
            "ss.compensationMessage = :message, ss.updatedAt = :updatedAt WHERE ss.id = :stepId")
    int markAsCompensated(@Param("stepId") Long stepId,
                          @Param("message") String message,
                          @Param("updatedAt") LocalDateTime updatedAt);

    // Find steps started within time range
    List<SagaStep> findByStartedAtBetween(LocalDateTime start, LocalDateTime end);

    // Get step statistics by status
    @Query("SELECT ss.stepStatus, COUNT(ss) FROM SagaStep ss GROUP BY ss.stepStatus")
    List<Object[]> getStepStatisticsByStatus();

    // Get step statistics by service
    @Query("SELECT ss.serviceName, ss.stepStatus, COUNT(ss) FROM SagaStep ss " +
            "GROUP BY ss.serviceName, ss.stepStatus")
    List<Object[]> getStepStatisticsByService();

    // Find steps with errors containing specific pattern
    @Query("SELECT ss FROM SagaStep ss WHERE ss.errorMessage LIKE %:errorPattern%")
    List<SagaStep> findByErrorMessageContaining(@Param("errorPattern") String errorPattern);

    // Get average execution time by step name
    @Query("SELECT ss.stepName, AVG(FUNCTION('TIMESTAMPDIFF', MINUTE, ss.startedAt, ss.completedAt)) " +
            "FROM SagaStep ss WHERE ss.stepStatus = 'COMPLETED' AND ss.startedAt IS NOT NULL " +
            "GROUP BY ss.stepName")
    List<Object[]> getAverageExecutionTimeByStepName();

    // Get average execution time by service
    @Query("SELECT ss.serviceName, AVG(FUNCTION('TIMESTAMPDIFF', MINUTE, ss.startedAt, ss.completedAt)) " +
            "FROM SagaStep ss WHERE ss.stepStatus = 'COMPLETED' AND ss.startedAt IS NOT NULL " +
            "GROUP BY ss.serviceName")
    List<Object[]> getAverageExecutionTimeByService();

    // Find long-running steps
    @Query("SELECT ss FROM SagaStep ss WHERE ss.stepStatus = 'IN_PROGRESS' " +
            "AND ss.startedAt < :threshold")
    List<SagaStep> findLongRunningSteps(@Param("threshold") LocalDateTime threshold);

    // Count total retries across all steps
    @Query("SELECT SUM(ss.retryCount) FROM SagaStep ss")
    Long getTotalRetryCount();

    // Find steps by step name across all sagas
    List<SagaStep> findByStepName(String stepName);

    // Find the last executed step for a saga
    @Query("SELECT ss FROM SagaStep ss WHERE ss.sagaTransaction.sagaId = :sagaId " +
            "AND ss.completedAt IS NOT NULL ORDER BY ss.completedAt DESC")
    Optional<SagaStep> findLastExecutedStep(@Param("sagaId") String sagaId);

    // Check if all steps are completed for a saga
    @Query("SELECT CASE WHEN COUNT(ss) = 0 THEN true ELSE false END FROM SagaStep ss " +
            "WHERE ss.sagaTransaction.sagaId = :sagaId AND ss.stepStatus != 'COMPLETED'")
    boolean areAllStepsCompleted(@Param("sagaId") String sagaId);

    // Find steps that need compensation in reverse order
    @Query("SELECT ss FROM SagaStep ss WHERE ss.sagaTransaction.id = :sagaTransactionId " +
            "AND ss.stepStatus = 'COMPLETED' AND ss.compensationExecuted = false " +
            "ORDER BY ss.stepOrder DESC")
    List<SagaStep> findStepsNeedingCompensationInReverseOrder(@Param("sagaTransactionId") Long sagaTransactionId);

    // Delete steps for old sagas (cleanup)
    @Modifying
    @Query("DELETE FROM SagaStep ss WHERE ss.sagaTransaction.id IN " +
            "(SELECT st.id FROM SagaTransaction st WHERE st.completedAt < :threshold)")
    int deleteStepsForOldSagas(@Param("threshold") LocalDateTime threshold);

    // Find steps with terminal status
    @Query("SELECT ss FROM SagaStep ss WHERE ss.stepStatus IN ('COMPLETED', 'COMPENSATED', 'FAILED') " +
            "AND ss.retryCount >= ss.maxRetries")
    List<SagaStep> findTerminalSteps();

    // Count pending steps for a saga
    @Query("SELECT COUNT(ss) FROM SagaStep ss WHERE ss.sagaTransaction.sagaId = :sagaId " +
            "AND ss.stepStatus = 'PENDING'")
    long countPendingStepsBySagaId(@Param("sagaId") String sagaId);
}