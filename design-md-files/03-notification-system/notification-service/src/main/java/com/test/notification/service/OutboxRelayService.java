package com.test.notification.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.test.notification.config.AppProperties;
import com.test.notification.domain.enums.OutboxStatus;
import com.test.notification.domain.model.OutboxEvent;
import com.test.notification.domain.repository.OutboxEventRepository;
import com.test.notification.kafka.event.NotificationRequestedEvent;
import com.test.notification.kafka.producer.NotificationEventProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxRelayService {

    private final OutboxEventRepository outboxRepository;
    private final NotificationEventProducer eventProducer;
    private final ObjectMapper objectMapper;
    private final AppProperties props;

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:200}")
    @Transactional
    public void relay() {
        List<OutboxEvent> pending = outboxRepository.findPendingEvents(
                OutboxStatus.PENDING,
                PageRequest.of(0, props.getOutbox().getBatchSize())
        );

        if (pending.isEmpty()) return;

        log.debug("Outbox relay processing {} events", pending.size());

        for (OutboxEvent event : pending) {
            try {
                NotificationRequestedEvent kafkaEvent = objectMapper.readValue(
                        event.getPayload(), NotificationRequestedEvent.class);

                eventProducer.publishNotificationRequested(kafkaEvent);

                event.setStatus(OutboxStatus.PUBLISHED);
                event.setPublishedAt(Instant.now());
            } catch (JsonProcessingException e) {
                log.error("Failed to deserialize outbox event id={} error={}", event.getEventId(), e.getMessage());
                event.setStatus(OutboxStatus.FAILED);
            }
            outboxRepository.save(event);
        }
    }
}
