package com.fintech.loan.service;

import com.fintech.loan.domain.entity.AuditLog;
import com.fintech.loan.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    public void log(String entityType, UUID entityId, String action,
                    UUID actorId, String actorType,
                    String oldStatus, String newStatus,
                    Map<String, Object> payload, String correlationId) {
        AuditLog entry = AuditLog.builder()
                .entityType(entityType)
                .entityId(entityId)
                .action(action)
                .actorId(actorId)
                .actorType(actorType)
                .oldStatus(oldStatus)
                .newStatus(newStatus)
                .changePayload(payload)
                .occurredAt(Instant.now())
                .correlationId(correlationId)
                .build();
        auditLogRepository.save(entry);
    }
}
