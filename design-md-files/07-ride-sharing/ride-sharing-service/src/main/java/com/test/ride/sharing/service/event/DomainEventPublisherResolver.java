package com.test.ride.sharing.service.event;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@EnableConfigurationProperties(EventProperties.class)
public class DomainEventPublisherResolver {

    private final Map<String, DomainEventPublisher> publishers;
    private final EventProperties properties;

    public DomainEventPublisherResolver(List<DomainEventPublisher> publishers, EventProperties properties) {
        this.publishers = publishers.stream()
                .collect(Collectors.toMap(DomainEventPublisher::name, Function.identity()));
        this.properties = properties;
    }

    public DomainEventPublisher active() {
        DomainEventPublisher publisher = publishers.get(properties.getBackend());
        if (publisher == null) {
            throw new IllegalStateException("No event publisher for app.events.backend="
                    + properties.getBackend() + ". Available: " + publishers.keySet());
        }
        return publisher;
    }
}
