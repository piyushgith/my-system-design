package com.test.ride.sharing.service.notification;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@EnableConfigurationProperties(NotificationProperties.class)
public class NotificationStrategyResolver {

    private final Map<String, NotificationStrategy> strategies;
    private final NotificationProperties properties;

    public NotificationStrategyResolver(List<NotificationStrategy> strategies, NotificationProperties properties) {
        this.strategies = strategies.stream()
                .collect(Collectors.toMap(NotificationStrategy::name, Function.identity()));
        this.properties = properties;
    }

    public NotificationStrategy active() {
        NotificationStrategy strategy = strategies.get(properties.getBackend());
        if (strategy == null) {
            throw new IllegalStateException("No notification backend for app.notification.backend="
                    + properties.getBackend() + ". Available: " + strategies.keySet());
        }
        return strategy;
    }

    public void notifyUser(UUID userId, String title, String body, String deepLink) {
        active().sendPush(userId, title, body, deepLink);
    }
}
