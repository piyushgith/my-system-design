package com.test.notification.kafka.producer;

import com.test.notification.config.AppProperties;
import com.test.notification.kafka.event.NotificationRequestedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationEventProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final AppProperties props;

    public void publishNotificationRequested(NotificationRequestedEvent event) {
        String topic = props.getKafka().getTopics().getNotificationRequested();
        String key = event.getNotificationId().toString();

        kafkaTemplate.send(topic, key, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish notification.requested id={} error={}",
                                event.getNotificationId(), ex.getMessage());
                    } else {
                        log.debug("Published notification.requested id={} partition={} offset={}",
                                event.getNotificationId(),
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });
    }
}
