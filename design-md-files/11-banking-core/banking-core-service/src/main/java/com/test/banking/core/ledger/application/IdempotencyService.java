package com.test.banking.core.ledger.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.test.banking.core.ledger.infrastructure.TransactionEntity;
import com.test.banking.core.ledger.infrastructure.TransactionRepository;
import com.test.banking.core.shared.exception.ConflictException;
import com.test.banking.core.shared.security.SecurityUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.function.Supplier;

@Service
public class IdempotencyService {

    private final TransactionRepository transactionRepository;
    private final ObjectMapper objectMapper;

    public IdempotencyService(TransactionRepository transactionRepository, ObjectMapper objectMapper) {
        this.transactionRepository = transactionRepository;
        this.objectMapper = objectMapper;
    }

    public <T> T execute(String idempotencyKey, String requestFingerprint, Class<T> responseType,
                         Supplier<T> action) {
        String initiatedBy = SecurityUtils.currentUserId();
        Optional<T> cached = findCachedResponse(idempotencyKey, initiatedBy, responseType);
        if (cached.isPresent()) {
            return cached.get();
        }

        Optional<TransactionEntity> inFlight = transactionRepository
                .findByIdempotencyKeyAndInitiatedBy(idempotencyKey, initiatedBy);
        if (inFlight.isPresent()) {
            TransactionEntity txn = inFlight.get();
            if ("PENDING".equals(txn.getStatus()) || txn.getResponseSnapshot() == null) {
                throw new ConflictException("IN_PROGRESS",
                        "Request with this idempotency key is still being processed");
            }
            return deserialize(txn, responseType)
                    .orElseThrow(() -> new ConflictException("IN_PROGRESS",
                            "Request with this idempotency key is still being processed"));
        }

        try {
            return action.get();
        } catch (RuntimeException ex) {
            if (!isDataIntegrityViolation(ex)) {
                throw ex;
            }
            return transactionRepository.findByIdempotencyKeyAndInitiatedBy(idempotencyKey, initiatedBy)
                    .flatMap(txn -> deserialize(txn, responseType))
                    .orElseThrow(() -> new ConflictException("DUPLICATE_TRANSACTION",
                            "Idempotency key collision; retry with the same key"));
        }
    }

    private boolean isDataIntegrityViolation(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof DataIntegrityViolationException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    public void assertPayloadMatches(String idempotencyKey, String requestFingerprint) {
        String initiatedBy = SecurityUtils.currentUserId();
        transactionRepository.findByIdempotencyKeyAndInitiatedBy(idempotencyKey, initiatedBy)
                .ifPresent(existing -> {
                    if (existing.getRequestFingerprint() == null) {
                        return;
                    }
                    if (requestFingerprint == null
                            || !existing.getRequestFingerprint().equals(requestFingerprint)) {
                        throw new ConflictException("DUPLICATE_TRANSACTION",
                                "Idempotency key already used with a different request payload");
                    }
                });
    }

    public <T> Optional<T> findCachedResponse(String idempotencyKey, Class<T> type) {
        return findCachedResponse(idempotencyKey, SecurityUtils.currentUserId(), type);
    }

    private <T> Optional<T> findCachedResponse(String idempotencyKey, String initiatedBy, Class<T> type) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        return transactionRepository.findByIdempotencyKeyAndInitiatedBy(idempotencyKey, initiatedBy)
                .filter(txn -> txn.getResponseSnapshot() != null)
                .flatMap(txn -> deserialize(txn, type));
    }

    private <T> Optional<T> deserialize(TransactionEntity txn, Class<T> type) {
        if (txn.getResponseSnapshot() == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(txn.getResponseSnapshot(), type));
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }
}
