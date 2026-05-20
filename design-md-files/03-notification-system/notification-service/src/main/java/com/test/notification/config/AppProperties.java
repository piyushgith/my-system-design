package com.test.notification.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app")
@Getter @Setter
public class AppProperties {

    private Kafka kafka = new Kafka();
    private Retry retry = new Retry();
    private Idempotency idempotency = new Idempotency();
    private Cache cache = new Cache();
    private Email email = new Email();
    private Outbox outbox = new Outbox();

    @Getter @Setter
    public static class Kafka {
        private Topics topics = new Topics();

        @Getter @Setter
        public static class Topics {
            private String notificationRequested = "notification.requested";
            private String emailDispatch = "email.dispatch";
            private String deliveryResult = "delivery.result";
            private String emailDlt = "email.dispatch.DLT";
        }
    }

    @Getter @Setter
    public static class Retry {
        private int maxAttempts = 3;
        private long initialIntervalMs = 1000L;
        private double multiplier = 2.0;
    }

    @Getter @Setter
    public static class Idempotency {
        private int ttlHours = 24;
    }

    @Getter @Setter
    public static class Cache {
        private int preferenceTtlMinutes = 60;
        private int templateTtlHours = 24;
    }

    @Getter @Setter
    public static class Email {
        private String provider = "mock";
        private SendGrid sendgrid = new SendGrid();
        private String fromAddress = "noreply@example.com";

        @Getter @Setter
        public static class SendGrid {
            private String apiKey = "mock-key";
        }
    }

    @Getter @Setter
    public static class Outbox {
        private long pollIntervalMs = 200L;
        private int batchSize = 100;
    }
}
