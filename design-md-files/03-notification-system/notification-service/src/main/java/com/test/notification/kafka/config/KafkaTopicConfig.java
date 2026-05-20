package com.test.notification.kafka.config;

import com.test.notification.config.AppProperties;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
@RequiredArgsConstructor
public class KafkaTopicConfig {

    private final AppProperties props;

    @Bean
    public NewTopic notificationRequestedTopic() {
        return TopicBuilder.name(props.getKafka().getTopics().getNotificationRequested())
                .partitions(4)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic emailDispatchTopic() {
        return TopicBuilder.name(props.getKafka().getTopics().getEmailDispatch())
                .partitions(4)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic deliveryResultTopic() {
        return TopicBuilder.name(props.getKafka().getTopics().getDeliveryResult())
                .partitions(4)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic emailDltTopic() {
        return TopicBuilder.name(props.getKafka().getTopics().getEmailDlt())
                .partitions(1)
                .replicas(1)
                .build();
    }
}
