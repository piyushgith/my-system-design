package com.test.banking.core.shared.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "audit_events", schema = "audit")
public class AuditEventEntity {

    @Id
    private UUID auditId;

    @Column(nullable = false)
    private String eventType;

    private String entityType;
    private String entityId;

    @Column(nullable = false)
    private String actorId;

    private String actorRole;
    private String actorIp;
    private String sessionId;
    private String correlationId;

    @JdbcTypeCode(SqlTypes.JSON)
    private String oldState;

    @JdbcTypeCode(SqlTypes.JSON)
    private String newState;

    @Column(nullable = false)
    private Instant occurredAt;
}
