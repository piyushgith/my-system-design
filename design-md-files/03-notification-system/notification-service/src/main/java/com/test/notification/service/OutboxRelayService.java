package com.test.notification.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.test.notification.config.AppProperties;
import com.test.notification.domain.enums.OutboxStatus;
import com.test.notification.domain.model.OutboxEvent;
import com.test.notification.domain.repository.OutboxEventRepository;
import com.test.notification.exception.KafkaPublishException;
import com.test.notification.kafka.event.NotificationRequestedEvent;
import com.test.notification.kafka.producer.NotificationEventProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxRelayService {

    private static final int MAX_RETRIES = 3;

    private final OutboxEventRepository outboxRepository;
    private final NotificationEventProducer eventProducer;
    private final ObjectMapper objectMapper;
    private final AppProperties props;

    // No @Transactional here: findPendingEvents and saveAll each run in their own
    // short transaction, so the DB connection is not held open during Kafka sends.
    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:200}")
    public void relay() {
        List<OutboxEvent> pending = outboxRepository.findPendingEvents(
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
                log.error("Outbox event deserialization failed id={} — marking FAILED permanently. error={}",
                        event.getEventId(), e.getMessage());
                event.setStatus(OutboxStatus.FAILED);
            } catch (KafkaPublishException e) {
                int attempts = event.getRetryCount() + 1;
                event.setRetryCount(attempts);
                if (attempts >= MAX_RETRIES) {
                    log.error("Outbox event id={} exceeded max retries ({}), marking FAILED. error={}",
                            event.getEventId(), MAX_RETRIES, e.getMessage());
                    event.setStatus(OutboxStatus.FAILED);
                } else {
                    log.warn("Outbox event id={} publish failed (attempt {}/{}), will retry next poll. error={}",
                            event.getEventId(), attempts, MAX_RETRIES, e.getMessage());
                    // status stays PENDING — picked up on next poll
                }
            }
        }

        outboxRepository.saveAll(pending);
    }
}
