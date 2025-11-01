package com.homeloan.saga.orchestrator.entity;


import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;


@NoArgsConstructor
@AllArgsConstructor
@Data
@Builder
@Entity
@Table(name = "saga_steps")
public class SagaStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "saga_transaction_id", nullable = false)
    @JsonIgnore
    private SagaTransaction sagaTransaction;

    @Column(name = "step_name", nullable = false)
    private String stepName;

    @Column(name = "step_order", nullable = false)
    private Integer stepOrder;

    @Column(name = "service_name", nullable = false)
    private String serviceName;

    @Enumerated(EnumType.STRING)
    @Column(name = "step_status", nullable = false)
    private StepStatus stepStatus;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "completed_at", nullable = false)
    private LocalDateTime completedAt;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private Integer retryCount = 0;

    @Column(name = "max_retries", nullable = false)
    @Builder.Default
    private Integer maxRetries = 3;

    @Column(name = "compensation_executed", nullable = false)
    @Builder.Default
    private Boolean compensationExecuted = false;

    @Column(name = "compensation_message", nullable = false)
    @Builder.Default
    private String compensationMessage = "";

    @PrePersist
    protected void onCreate() {
        if (this.stepStatus == StepStatus.IN_PROGRESS && this.startedAt == null) {
            this.startedAt = LocalDateTime.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        if (this.stepStatus == StepStatus.IN_PROGRESS && this.startedAt == null) {
            this.startedAt = LocalDateTime.now();
        }
        if ((this.stepStatus == StepStatus.IN_PROGRESS ||
                this.stepStatus == StepStatus.FAILED ||
                this.stepStatus == StepStatus.COMPENSATED) && this.completedAt == null){
            this.completedAt = LocalDateTime.now();
        }
    }


    public boolean canRetry(){
        return stepStatus == StepStatus.FAILED && retryCount < maxRetries;
    }

    public boolean isTerminal(){
        return stepStatus == StepStatus.COMPLETED ||
                stepStatus == StepStatus.COMPENSATED ||
                (stepStatus == StepStatus.FAILED && !canRetry());
    }

    public Long getExecutionDurationsMinutes(){
        if (startedAt == null) return null;

        LocalDateTime endTime = completedAt !=null ? completedAt : LocalDateTime.now();
        return java.time.Duration.between(startedAt,endTime).toMinutes();
    }
}
