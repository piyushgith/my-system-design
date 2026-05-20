package com.test.banking.core.shared.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.test.banking.core.shared.web.CorrelationIdFilter;
import org.slf4j.MDC;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class AuditService {

    private final AuditEventRepository repository;
    private final ObjectMapper objectMapper;

    public AuditService(AuditEventRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public void record(String eventType, String entityType, String entityId, Object newState) {
        AuditEventEntity event = new AuditEventEntity();
        event.setAuditId(UUID.randomUUID());
        event.setEventType(eventType);
        event.setEntityType(entityType);
        event.setEntityId(entityId);
        event.setActorId(currentActorId());
        event.setActorRole(currentActorRole());
        event.setCorrelationId(MDC.get(CorrelationIdFilter.MDC_KEY));
        event.setNewState(toJson(newState));
        event.setOccurredAt(Instant.now());
        repository.save(event);
    }

    private String currentActorId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "SYSTEM";
    }

    private String currentActorRole() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getAuthorities().isEmpty()) {
            return "SYSTEM";
        }
        return auth.getAuthorities().iterator().next().getAuthority().replace("ROLE_", "");
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{\"error\":\"serialization_failed\"}";
        }
    }
}
