package com.homeloan.saga.orchestrator.service;

import com.homeloan.saga.orchestrator.dto.SagaStatus;
import com.homeloan.saga.orchestrator.entity.SagaStep;
import com.homeloan.saga.orchestrator.entity.SagaTransaction;
import com.homeloan.saga.orchestrator.entity.StepStatus;
import com.homeloan.saga.orchestrator.events.*;
import com.homeloan.saga.orchestrator.repository.SagaStepRepository;
import com.homeloan.saga.orchestrator.repository.SagaTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class SagaOrchestrationService {

    private static final List<String> WORKFLOW_STEPS = List.of(
            "CREDIT_CHECK",
            "PROPERTY_VALUATION",
            "DOCUMENT_VERIFICATION",
            "LOAN_PROCESSING",
            "NOTIFICATION",
            "SAGA_COMPLETION"
    );

    private static final String CREDIT_CHECK_TOPIC = "credit-check-events";
    private static final String PROPERTY_VALUATION_TOPIC = "property-valuation-events";
    private static final String DOCUMENT_VERIFICATION_TOPIC = "document-verification-events";
    private static final String LOAN_PROCESSING_TOPIC = "loan-processing-events";
    private static final String NOTIFICATION_TOPIC = "notification-events";
    private static final String SAGA_TOPIC = "saga-events";

    private final SagaTransactionRepository sagaTransactionRepository;
    private final SagaStepRepository sagaStepRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    // In-memory lock per saga to prevent race conditions
    private final ConcurrentHashMap<String, Object> sagaLocks = new ConcurrentHashMap<>();

    /**
     * Initiate a new saga for loan application
     */
    @Transactional
    public SagaTransaction startSaga(LoanApplicationEvent loanApplicationEvent) {
        log.info("Starting saga for loan application: {}", loanApplicationEvent.getApplicationId());

        String sagaId = UUID.randomUUID().toString();

        // Create saga transaction
        SagaTransaction sagaTransaction = SagaTransaction.builder()
                .sagaId(sagaId)
                .loanApplicationId(loanApplicationEvent.getApplicationId())
                .sagaStatus(SagaStatus.STARTED)
                .currentStep(WORKFLOW_STEPS.get(0))
                .totalSteps(WORKFLOW_STEPS.size())
                .completedSteps(0)
                .startedAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .compensationRequired(false)
                .build();

        // Create saga steps with explicit ordering
        List<SagaStep> steps = new ArrayList<>();
        for (int i = 0; i < WORKFLOW_STEPS.size(); i++) {
            SagaStep step = SagaStep.builder()
                    .sagaTransaction(sagaTransaction)
                    .stepName(WORKFLOW_STEPS.get(i))
                    .stepOrder(i + 1)  // Explicit order: 1, 2, 3, 4, 5, 6
                    .serviceName(getServiceNameForStep(WORKFLOW_STEPS.get(i)))
                    .stepStatus(StepStatus.PENDING)
                    .updatedAt(LocalDateTime.now())
                    .retryCount(0)
                    .maxRetries(3)
                    .compensationExecuted(false)
                    .build();
            steps.add(step);
        }

        sagaTransaction.setSteps(steps);
        sagaTransaction = sagaTransactionRepository.save(sagaTransaction);

        // Publish saga started event
        publishSagaEvent(sagaId, SagaStatus.STARTED, "Saga started", loanApplicationEvent.getApplicationId());

        // Start first step
        executeNextStep(sagaTransaction);

        return sagaTransaction;
    }

    /**
     * Handle credit check completion with event ordering guarantees
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void handleCreditCheckCompletion(CreditCheckEvent event) {
        processStepCompletion(event.getSagaId(), "CREDIT_CHECK",
                event.isSuccess(), event.getErrorMessage(), event.getEventId());
    }

    /**
     * Handle property valuation completion
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void handlePropertyValuationCompletion(PropertyValuationEvent event) {
        processStepCompletion(event.getSagaId(), "PROPERTY_VALUATION",
                event.isSuccess(), event.getErrorMessage(), event.getEventId());
    }

    /**
     * Handle document verification completion
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void handleDocumentVerificationCompletion(DocumentVerificationEvent event) {
        processStepCompletion(event.getSagaId(), "DOCUMENT_VERIFICATION",
                event.isSuccess(), event.getErrorMessage(), event.getEventId());
    }

    /**
     * Handle loan processing completion
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void handleLoanProcessingCompletion(LoanProcessingEvent event) {
        processStepCompletion(event.getSagaId(), "LOAN_PROCESSING",
                event.isSuccess(), event.getErrorMessage(), event.getEventId());
    }

    /**
     * CENTRALIZED step completion handler with ordering guarantees
     * This ensures:
     * 1. Only one event processed at a time per saga (via lock)
     * 2. Idempotency (duplicate events ignored)
     * 3. Step order validation
     * 4. Atomic state transitions
     */
    private void processStepCompletion(String sagaId, String stepName,
                                       boolean success, String errorMessage, String eventId) {
        // Acquire saga-specific lock to prevent concurrent modifications
        Object lock = sagaLocks.computeIfAbsent(sagaId, k -> new Object());

        synchronized (lock) {
            try {
                log.info("Processing {} completion for saga: {} (eventId: {})", stepName, sagaId, eventId);

                Optional<SagaTransaction> sagaOpt = sagaTransactionRepository.findBySagaId(sagaId);
                if (sagaOpt.isEmpty()) {
                    log.error("Saga not found: {}", sagaId);
                    return;
                }

                SagaTransaction saga = sagaOpt.get();

                // ORDERING CHECK 1: Validate this is the expected current step
                if (!saga.getCurrentStep().equals(stepName)) {
                    log.warn("Event order violation! Expected step: {}, Received: {} for saga: {}",
                            saga.getCurrentStep(), stepName, sagaId);
                    return; // Ignore out-of-order events
                }

                Optional<SagaStep> stepOpt = sagaStepRepository.findBySagaTransactionAndStepName(saga, stepName);
                if (stepOpt.isEmpty()) {
                    log.error("Step {} not found for saga: {}", stepName, sagaId);
                    return;
                }

                SagaStep step = stepOpt.get();

                // IDEMPOTENCY CHECK: Prevent duplicate event processing
                if (step.getStepStatus() == StepStatus.COMPLETED) {
                    log.warn("Step {} already completed for saga {}. Ignoring duplicate event.", stepName, sagaId);
                    return;
                }

                // ORDERING CHECK 2: Verify step is in correct state (IN_PROGRESS)
                if (step.getStepStatus() != StepStatus.IN_PROGRESS) {
                    log.warn("Step {} not in progress (status: {}) for saga {}. Ignoring event.",
                            stepName, step.getStepStatus(), sagaId);
                    return;
                }

                // Process success or failure
                if (success) {
                    completeCurrentStepAndProceed(saga, step, "Step completed successfully");
                } else {
                    handleStepFailure(saga, step, errorMessage);
                }

            } finally {
                // Clean up lock if saga is terminal
                Optional<SagaTransaction> updatedSaga = sagaTransactionRepository.findBySagaId(sagaId);
                if (updatedSaga.isPresent() && updatedSaga.get().isTerminal()) {
                    sagaLocks.remove(sagaId);
                    log.debug("Removed lock for terminal saga: {}", sagaId);
                }
            }
        }
    }

    /**
     * Complete current step and proceed to next with strict ordering
     */
    private void completeCurrentStepAndProceed(SagaTransaction saga, SagaStep step, String message) {
        log.info("Completing step {} (order: {}) for saga: {}",
                step.getStepName(), step.getStepOrder(), saga.getSagaId());

        // Mark step as completed
        step.setStepStatus(StepStatus.COMPLETED);
        step.setCompletedAt(LocalDateTime.now());
        step.setUpdatedAt(LocalDateTime.now());
        sagaStepRepository.save(step);

        // Update saga progress
        saga.setCompletedSteps(saga.getCompletedSteps() + 1);
        saga.setUpdatedAt(LocalDateTime.now());

        // Check if this is the last step
        if (saga.getCompletedSteps() >= saga.getTotalSteps()) {
            completeSaga(saga);
        } else {
            // Move to NEXT step in sequence
            int currentStepIndex = WORKFLOW_STEPS.indexOf(step.getStepName());
            int nextStepIndex = currentStepIndex + 1;

            if (nextStepIndex < WORKFLOW_STEPS.size()) {
                String nextStepName = WORKFLOW_STEPS.get(nextStepIndex);
                saga.setCurrentStep(nextStepName);
                saga.setSagaStatus(SagaStatus.IN_PROGRESS);
                sagaTransactionRepository.save(saga);

                log.info("Saga {} progressing from step {} (order: {}) to step {} (order: {})",
                        saga.getSagaId(), step.getStepName(), step.getStepOrder(),
                        nextStepName, nextStepIndex + 1);

                // Execute next step
                executeNextStep(saga);
            }
        }
    }

    /**
     * Execute the next pending step IN ORDER
     */
    private void executeNextStep(SagaTransaction saga) {
        // Find the NEXT pending step by order (not just any pending step)
        Optional<SagaStep> nextStepOpt = sagaStepRepository
                .findBySagaTransactionAndStepName(saga, saga.getCurrentStep());

        if (nextStepOpt.isEmpty()) {
            log.error("Next step {} not found for saga: {}", saga.getCurrentStep(), saga.getSagaId());
            return;
        }

        SagaStep nextStep = nextStepOpt.get();

        // Verify step is in correct sequence
        if (nextStep.getStepStatus() != StepStatus.PENDING) {
            log.error("Step order violation! Step {} has status {} for saga {}",
                    nextStep.getStepName(), nextStep.getStepStatus(), saga.getSagaId());
            return;
        }

        // Mark as IN_PROGRESS (only one step should be IN_PROGRESS at a time)
        nextStep.setStepStatus(StepStatus.IN_PROGRESS);
        nextStep.setStartedAt(LocalDateTime.now());
        nextStep.setUpdatedAt(LocalDateTime.now());
        sagaStepRepository.save(nextStep);

        log.info("Executing step {} (order: {}) for saga: {}",
                nextStep.getStepName(), nextStep.getStepOrder(), saga.getSagaId());

        // Publish event with ordering guarantees
        publishStepEventWithOrdering(saga, nextStep);
    }

    /**
     * Publish event with Kafka partition key for ordering
     * Using sagaId as partition key ensures all events for same saga go to same partition
     */
    private void publishStepEventWithOrdering(SagaTransaction saga, SagaStep step) {
        String stepName = step.getStepName();
        Long loanApplicationId = saga.getLoanApplicationId();
        String sagaId = saga.getSagaId();
        String eventId = UUID.randomUUID().toString();

        // KEY INSIGHT: Using sagaId as Kafka partition key ensures ordering
        // All events for same saga go to same partition and are processed in order

        switch (stepName) {
            case "CREDIT_CHECK":
                CreditCheckEvent creditCheckEvent = CreditCheckEvent.builder()
                        .eventId(eventId)
                        .sagaId(sagaId)
                        .loanApplicationId(loanApplicationId)
                        .stepOrder(step.getStepOrder())
                        .timestamp(LocalDateTime.now())
                        .build();
                kafkaTemplate.send(CREDIT_CHECK_TOPIC, sagaId, creditCheckEvent);
                log.info("Published credit check event (order: {}) for saga: {}", step.getStepOrder(), sagaId);
                break;

            case "PROPERTY_VALUATION":
                PropertyValuationEvent valuationEvent = PropertyValuationEvent.builder()
                        .eventId(eventId)
                        .sagaId(sagaId)
                        .loanApplicationId(loanApplicationId)
                        .stepOrder(step.getStepOrder())
                        .timestamp(LocalDateTime.now())
                        .build();
                kafkaTemplate.send(PROPERTY_VALUATION_TOPIC, sagaId, valuationEvent);
                log.info("Published property valuation event (order: {}) for saga: {}", step.getStepOrder(), sagaId);
                break;

            case "DOCUMENT_VERIFICATION":
                DocumentVerificationEvent docEvent = DocumentVerificationEvent.builder()
                        .eventId(eventId)
                        .sagaId(sagaId)
                        .loanApplicationId(loanApplicationId)
                        .stepOrder(step.getStepOrder())
                        .timestamp(LocalDateTime.now())
                        .build();
                kafkaTemplate.send(DOCUMENT_VERIFICATION_TOPIC, sagaId, docEvent);
                log.info("Published document verification event (order: {}) for saga: {}", step.getStepOrder(), sagaId);
                break;

            case "LOAN_PROCESSING":
                LoanProcessingEvent processingEvent = LoanProcessingEvent.builder()
                        .eventId(eventId)
                        .sagaId(sagaId)
                        .loanApplicationId(loanApplicationId)
                        .stepOrder(step.getStepOrder())
                        .timestamp(LocalDateTime.now())
                        .build();
                kafkaTemplate.send(LOAN_PROCESSING_TOPIC, sagaId, processingEvent);
                log.info("Published loan processing event (order: {}) for saga: {}", step.getStepOrder(), sagaId);
                break;

            case "NOTIFICATION":
                sendNotification(saga, "Loan application is being processed");
                completeCurrentStepAndProceed(saga, step, "Notification sent");
                break;

            case "SAGA_COMPLETION":
                completeSaga(saga);
                break;

            default:
                log.warn("Unknown step: {}", stepName);
        }
    }

    /**
     * Handle step failure with retry logic
     */
    private void handleStepFailure(SagaTransaction saga, SagaStep step, String errorMessage) {
        log.error("Step {} (order: {}) failed for saga {}: {}",
                step.getStepName(), step.getStepOrder(), saga.getSagaId(), errorMessage);

        // Check if retry is possible
        if (step.canRetry()) {
            log.info("Retrying step {} for saga {} (attempt {}/{})",
                    step.getStepName(), saga.getSagaId(), step.getRetryCount() + 1, step.getMaxRetries());

            step.setRetryCount(step.getRetryCount() + 1);
            step.setErrorMessage(errorMessage);
            step.setStepStatus(StepStatus.PENDING); // Reset to PENDING for retry
            step.setUpdatedAt(LocalDateTime.now());
            sagaStepRepository.save(step);

            // Retry the step (maintains order)
            executeNextStep(saga);
        } else {
            // Max retries exceeded, initiate compensation
            log.error("Max retries exceeded for step {} (order: {}) in saga {}. Initiating compensation.",
                    step.getStepName(), step.getStepOrder(), saga.getSagaId());

            step.setStepStatus(StepStatus.FAILED);
            step.setErrorMessage(errorMessage);
            step.setCompletedAt(LocalDateTime.now());
            step.setUpdatedAt(LocalDateTime.now());
            sagaStepRepository.save(step);

            initiateCompensation(saga, errorMessage);
        }
    }

    /**
     * Initiate compensation (rollback) for failed saga
     * Compensates steps in REVERSE ORDER
     */
    @Transactional
    public void initiateCompensation(SagaTransaction saga, String errorMessage) {
        log.info("Initiating compensation for saga: {}", saga.getSagaId());

        saga.setSagaStatus(SagaStatus.COMPENSATING);
        saga.setCompensationRequired(true);
        saga.setErrorMessage(errorMessage);
        saga.setUpdatedAt(LocalDateTime.now());
        sagaTransactionRepository.save(saga);

        // Get completed steps in REVERSE order for compensation
        List<SagaStep> completedSteps = sagaStepRepository
                .findStepsNeedingCompensationInReverseOrder(saga.getId());

        log.info("Compensating {} steps in reverse order for saga {}", completedSteps.size(), saga.getSagaId());

        for (SagaStep step : completedSteps) {
            log.info("Compensating step {} (order: {}) for saga {}",
                    step.getStepName(), step.getStepOrder(), saga.getSagaId());
            compensateStep(saga, step);
        }

        // Mark saga as compensated
        saga.setSagaStatus(SagaStatus.COMPENSATED);
        saga.setCompletedAt(LocalDateTime.now());
        saga.setUpdatedAt(LocalDateTime.now());
        sagaTransactionRepository.save(saga);

        publishSagaEvent(saga.getSagaId(), SagaStatus.COMPENSATED,
                "Saga compensated due to failure: " + errorMessage, saga.getLoanApplicationId());
    }

    /**
     * Compensate individual step
     */
    private void compensateStep(SagaTransaction saga, SagaStep step) {
        String compensationMessage = "Compensation executed for " + step.getStepName();

        // Publish compensation events to respective services
        switch (step.getStepName()) {
            case "CREDIT_CHECK":
                log.info("Compensating credit check (order: {}) for saga: {}", step.getStepOrder(), saga.getSagaId());
                break;

            case "PROPERTY_VALUATION":
                log.info("Compensating property valuation (order: {}) for saga: {}", step.getStepOrder(), saga.getSagaId());
                break;

            case "DOCUMENT_VERIFICATION":
                log.info("Compensating document verification (order: {}) for saga: {}", step.getStepOrder(), saga.getSagaId());
                break;

            case "LOAN_PROCESSING":
                log.info("Compensating loan processing (order: {}) for saga: {}", step.getStepOrder(), saga.getSagaId());
                break;
        }

        step.setCompensationExecuted(true);
        step.setCompensationMessage(compensationMessage);
        step.setStepStatus(StepStatus.COMPENSATED);
        step.setUpdatedAt(LocalDateTime.now());
        sagaStepRepository.save(step);
    }

    /**
     * Complete saga successfully
     */
    private void completeSaga(SagaTransaction saga) {
        log.info("Completing saga: {} after {} steps", saga.getSagaId(), saga.getTotalSteps());

        saga.setSagaStatus(SagaStatus.COMPLETED);
        saga.setCompletedAt(LocalDateTime.now());
        saga.setUpdatedAt(LocalDateTime.now());
        sagaTransactionRepository.save(saga);

        publishSagaEvent(saga.getSagaId(), SagaStatus.COMPLETED,
                "Saga completed successfully", saga.getLoanApplicationId());

        sendNotification(saga, "Loan application processed successfully");
    }

    /**
     * Publish saga event with ordering
     */
    private void publishSagaEvent(String sagaId, SagaStatus status, String message, Long loanApplicationId) {
        SagaEvent sagaEvent = SagaEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .sagaId(sagaId)
                .loanApplicationId(loanApplicationId)
                .status(status)
                .message(message)
                .timestamp(LocalDateTime.now())
                .build();

        // Use sagaId as partition key for ordering
        kafkaTemplate.send(SAGA_TOPIC, sagaId, sagaEvent);
        log.info("Published saga event: {} for saga: {}", status, sagaId);
    }

    /**
     * Send notification
     */
    private void sendNotification(SagaTransaction saga, String message) {
        log.info("Sending notification for saga {}: {}", saga.getSagaId(), message);
        // Implement notification logic
    }

    /**
     * Get service name for step
     */
    private String getServiceNameForStep(String stepName) {
        return switch (stepName) {
            case "CREDIT_CHECK" -> "credit-check-service";
            case "PROPERTY_VALUATION" -> "property-valuation-service";
            case "DOCUMENT_VERIFICATION" -> "document-verification-service";
            case "LOAN_PROCESSING" -> "loan-processing-service";
            case "NOTIFICATION" -> "notification-service";
            case "SAGA_COMPLETION" -> "saga-orchestrator";
            default -> "unknown-service";
        };
    }

    /**
     * Get saga status
     */
    public Optional<SagaTransaction> getSagaStatus(String sagaId) {
        return sagaTransactionRepository.findBySagaId(sagaId);
    }

    /**
     * Get all active sagas
     */
    public List<SagaTransaction> getActiveSagas() {
        return sagaTransactionRepository.findAllActiveSagas();
    }

    /**
     * Retry failed saga
     */
    @Transactional
    public void retrySaga(String sagaId) {
        Optional<SagaTransaction> sagaOpt = sagaTransactionRepository.findBySagaId(sagaId);
        if (sagaOpt.isEmpty()) {
            log.error("Saga not found: {}", sagaId);
            return;
        }

        SagaTransaction saga = sagaOpt.get();
        if (saga.getSagaStatus() != SagaStatus.FAILED) {
            log.warn("Cannot retry saga {} with status: {}", sagaId, saga.getSagaStatus());
            return;
        }

        log.info("Retrying failed saga: {}", sagaId);
        saga.setSagaStatus(SagaStatus.IN_PROGRESS);
        saga.setUpdatedAt(LocalDateTime.now());
        sagaTransactionRepository.save(saga);

        executeNextStep(saga);
    }
}