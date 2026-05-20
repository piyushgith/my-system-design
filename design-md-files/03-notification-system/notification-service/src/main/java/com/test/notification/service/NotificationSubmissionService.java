package com.test.notification.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.test.notification.config.AppProperties;
import com.test.notification.domain.enums.NotificationStatus;
import com.test.notification.domain.enums.OutboxStatus;
import com.test.notification.domain.model.NotificationRequest;
import com.test.notification.domain.model.OutboxEvent;
import com.test.notification.domain.repository.NotificationRequestRepository;
import com.test.notification.domain.repository.OutboxEventRepository;
import com.test.notification.exception.DuplicateIdempotencyKeyException;
import com.test.notification.exception.OutboxSerializationException;
import com.test.notification.kafka.event.NotificationRequestedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationSubmissionService {

    private final NotificationRequestRepository notificationRepository;
    private final OutboxEventRepository outboxRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final AppProperties props;

    private static final String IDEMPOTENCY_PREFIX = "idempotency:";

    @Transactional
    public NotificationRequest submit(NotificationRequest request) {
        checkIdempotency(request.getIdempotencyKey());

        request.setNotificationId(UUID.randomUUID());
        request.setStatus(NotificationStatus.PENDING);
        request.setCreatedAt(Instant.now());

        NotificationRequest saved = notificationRepository.save(request);

        OutboxEvent outboxEvent = buildOutboxEvent(saved);
        outboxRepository.save(outboxEvent);

        // Store idempotency key in Redis so the outbox relay can also fast-check
        redisTemplate.opsForValue().set(
                IDEMPOTENCY_PREFIX + request.getIdempotencyKey(),
                saved.getNotificationId().toString(),
                Duration.ofHours(props.getIdempotency().getTtlHours())
        );

        log.info("Notification accepted id={} idempotencyKey={} category={} priority={}",
                saved.getNotificationId(), saved.getIdempotencyKey(),
                saved.getCategory(), saved.getPriority());

        return saved;
    }

    @Transactional
    public boolean cancel(UUID notificationId) {
        return notificationRepository.findById(notificationId).map(n -> {
            if (n.getStatus() == NotificationStatus.PENDING
                    || n.getStatus() == NotificationStatus.DISPATCHED) {
                n.setStatus(NotificationStatus.CANCELLED);
                n.setCompletedAt(Instant.now());
                notificationRepository.save(n);
                return true;
            }
            return false;
        }).orElse(false);
    }

    private void checkIdempotency(String idempotencyKey) {
        // Fast path: Redis check
        String existing = redisTemplate.opsForValue().get(IDEMPOTENCY_PREFIX + idempotencyKey);
        if (existing != null) {
            throw new DuplicateIdempotencyKeyException(idempotencyKey, UUID.fromString(existing));
        }
        // Slow path: DB check (handles Redis eviction edge case)
        notificationRepository.findByIdempotencyKey(idempotencyKey).ifPresent(n -> {
            throw new DuplicateIdempotencyKeyException(idempotencyKey, n.getNotificationId());
        });
    }

    private OutboxEvent buildOutboxEvent(NotificationRequest request) {
        NotificationRequestedEvent event = NotificationRequestedEvent.builder()
                .notificationId(request.getNotificationId())
                .idempotencyKey(request.getIdempotencyKey())
                .category(request.getCategory())
                .priority(request.getPriority())
                .templateId(request.getTemplateId())
                .templateVersion(request.getTemplateVersion())
                .recipientUserId(request.getRecipientUserId())
                .batchId(request.getBatchId())
                .variables(request.getVariables())
                .channelsOverride(request.getChannelsOverride())
                .scheduledAt(request.getScheduledAt())
                .expiresAt(request.getExpiresAt())
                .producerService(request.getProducerService())
                .producerTraceId(request.getProducerTraceId())
                .eventTimestamp(Instant.now())
                .build();

        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new OutboxSerializationException(request.getNotificationId(), e);
        }

        return OutboxEvent.builder()
                .aggregateType("notification_request")
                .aggregateId(request.getNotificationId())
                .eventType("notification.requested")
                .payload(payload)
                .status(OutboxStatus.PENDING)
                .build();
    }
}
