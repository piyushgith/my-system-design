package com.test.notification.kafka.producer;

import com.test.notification.config.AppProperties;
import com.test.notification.exception.KafkaPublishException;
import com.test.notification.kafka.event.NotificationRequestedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationEventProducer {

    private static final long PUBLISH_TIMEOUT_SECONDS = 5;

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final AppProperties props;

    public void publishNotificationRequested(NotificationRequestedEvent event) {
        String topic = props.getKafka().getTopics().getNotificationRequested();
        String key = event.getNotificationId().toString();

        try {
            var result = kafkaTemplate.send(topic, key, event).get(PUBLISH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            log.debug("Published notification.requested id={} partition={} offset={}",
                    event.getNotificationId(),
                    result.getRecordMetadata().partition(),
                    result.getRecordMetadata().offset());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KafkaPublishException(
                    "Interrupted while publishing notificationId=" + event.getNotificationId(), e);
        } catch (Exception e) {
            throw new KafkaPublishException(
                    "Failed to publish notificationId=" + event.getNotificationId(), e);
        }
    }
}
