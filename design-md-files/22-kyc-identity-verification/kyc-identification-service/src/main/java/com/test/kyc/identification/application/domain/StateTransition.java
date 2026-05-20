package com.test.kyc.identification.application.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "state_transitions")
@Getter
@NoArgsConstructor
public class StateTransition {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "transition_id", updatable = false, nullable = false)
    private UUID transitionId;

    @Column(name = "application_id", nullable = false, updatable = false)
    private UUID applicationId;

    @Column(name = "from_status", length = 60, updatable = false)
    private String fromStatus;

    @Column(name = "to_status", nullable = false, length = 60, updatable = false)
    private String toStatus;

    @Column(name = "trigger_source", nullable = false, length = 20, updatable = false)
    private String triggerSource;

    @Column(name = "triggered_by", nullable = false, length = 255, updatable = false)
    private String triggeredBy;

    @Column(name = "reason", nullable = false, updatable = false)
    private String reason;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb", updatable = false)
    private Map<String, Object> metadata;

    public static StateTransition of(UUID applicationId,
                                     KycStatus from,
                                     KycStatus to,
                                     String triggerSource,
                                     String triggeredBy,
                                     String reason) {
        var t = new StateTransition();
        t.applicationId = applicationId;
        t.fromStatus = from != null ? from.name() : null;
        t.toStatus = to.name();
        t.triggerSource = triggerSource;
        t.triggeredBy = triggeredBy;
        t.reason = reason;
        t.occurredAt = Instant.now();
        return t;
    }
}
