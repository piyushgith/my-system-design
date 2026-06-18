package com.test.ride.sharing.service.shared;

import com.test.ride.sharing.service.web.error.BusinessRuleException;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

@Service
public class IdempotencyService {

    private final IdempotencyRecordRepository repository;
    private final ObjectMapper objectMapper;

    public IdempotencyService(IdempotencyRecordRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public Optional<ResponseEntity<Object>> findCached(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        return repository.findById(idempotencyKey).map(record -> {
            try {
                Object body = objectMapper.readValue(record.getResponseBody(), Object.class);
                return ResponseEntity.status(record.getHttpStatus()).body(body);
            } catch (JacksonException ex) {
                throw new IllegalStateException("Corrupt idempotency cache for key " + idempotencyKey, ex);
            }
        });
    }

    @Transactional
    public void store(String idempotencyKey, int httpStatus, Object body) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return;
        }
        if (repository.existsById(idempotencyKey)) {
            throw new BusinessRuleException("IDEMPOTENCY_CONFLICT", "Idempotency key already used");
        }
        IdempotencyRecord record = new IdempotencyRecord();
        record.setIdempotencyKey(idempotencyKey);
        record.setHttpStatus(httpStatus);
        try {
            record.setResponseBody(objectMapper.writeValueAsString(body));
        } catch (JacksonException ex) {
            throw new IllegalStateException("Failed to serialize idempotency response", ex);
        }
        repository.save(record);
    }
}
