package com.test.notification.kafka.config;

import com.test.notification.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;


@Slf4j
@Configuration
@EnableKafka
@RequiredArgsConstructor
public class KafkaConsumerConfig {

    private final AppProperties props;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
            ConsumerFactory<String, Object> consumerFactory) {

        var factory = new ConcurrentKafkaListenerContainerFactory<String, Object>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(errorHandler());
        return factory;
    }

    private DefaultErrorHandler errorHandler() {
        AppProperties.Retry retryProps = props.getRetry();

        ExponentialBackOff backOff = new ExponentialBackOff(
                retryProps.getInitialIntervalMs(),
                retryProps.getMultiplier()
        );
        backOff.setMaxAttempts(retryProps.getMaxAttempts());

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (ConsumerRecord<?, ?> r, Exception e) -> {
                    log.error("Message sent to DLT after {} retries. topic={} key={} error={}",
                            retryProps.getMaxAttempts(), r.topic(), r.key(), e.getMessage());
                    return new org.apache.kafka.common.TopicPartition(
                            props.getKafka().getTopics().getEmailDlt(), 0);
                }
        );

        return new DefaultErrorHandler(recoverer, backOff);
    }
}
