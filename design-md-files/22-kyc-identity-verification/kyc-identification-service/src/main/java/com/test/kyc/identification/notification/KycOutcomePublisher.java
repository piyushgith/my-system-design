package com.test.kyc.identification.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class KycOutcomePublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${kyc.kafka.topics.outcome}")
    private String outcomeTopic;

    @Value("${kyc.kafka.topics.manual-review}")
    private String manualReviewTopic;

    public void publishOutcome(UUID applicationId, String outcome, String rejectionReason) {
        var event = new KycOutcomeEvent(applicationId, outcome, rejectionReason, Instant.now());
        kafkaTemplate.send(outcomeTopic, applicationId.toString(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish outcome event for application={}", applicationId, ex);
                    } else {
                        log.debug("Published outcome={} for application={}", outcome, applicationId);
                    }
                });
    }

    public void publishManualReviewRequired(UUID applicationId, String routingReason, String priority) {
        var event = new KycManualReviewEvent(applicationId, routingReason, priority, Instant.now());
        kafkaTemplate.send(manualReviewTopic, applicationId.toString(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish manual-review event for application={}", applicationId, ex);
                    }
                });
    }
}
