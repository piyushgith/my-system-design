package com.homeloan.saga.orchestrator.entity;

import com.homeloan.saga.orchestrator.dto.SagaStatus;
import com.homeloan.saga.orchestrator.entity.SagaStep;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;


@NoArgsConstructor
@AllArgsConstructor
@Data
@Builder
@Entity
@Table(name = "saga_transactions")
public class SagaTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "saga_id", nullable = false)
    private String sagaId;

    @Column(name = "loan_application_id", nullable = false)
    private Long loanApplicationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "saga_status", nullable = false)
    private SagaStatus sagaStatus;

    @Column(name = "current_step", nullable = false)
    private String currentStep;

    @Column(name = "total_steps", nullable = false)
    private Integer totalSteps;

    @Column(name = "completed_steps", nullable = false)
    private Integer completedSteps;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "completed_at", nullable = false)
    private LocalDateTime completedAt;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "compensation_required", nullable = false)
    @Builder.Default
    private Boolean compensationRequired = false;

    @OneToMany(mappedBy = "sagaTransaction", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    private List<SagaStep> steps = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.completedAt = now;
        this.updatedAt = now;
        if (this.completedSteps == null) this.completedSteps = 0;
        if (this.totalSteps == null) this.totalSteps = 6; //Default steps in loan workflow
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
        if (this.sagaStatus == SagaStatus.COMPLETED || this.sagaStatus == SagaStatus.FAILED || this.sagaStatus == SagaStatus.COMPENSATED) {
            this.completedAt = LocalDateTime.now();
        }

    }

    public boolean isTerminal() {
        return (sagaStatus == SagaStatus.COMPLETED || sagaStatus == SagaStatus.COMPENSATED || sagaStatus == SagaStatus.FAILED);
    }

    /**
     * Calculate saga progress percentage
     */
    public double getProgressPercentage() {
        if (totalSteps == null || totalSteps == 0) return 0.0;
        return (completedSteps.doubleValue() / totalSteps.doubleValue()) * 100.0;
    }

}
