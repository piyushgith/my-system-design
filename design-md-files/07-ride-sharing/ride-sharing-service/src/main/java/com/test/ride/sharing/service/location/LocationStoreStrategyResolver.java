package com.test.ride.sharing.service.location;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@EnableConfigurationProperties(LocationProperties.class)
public class LocationStoreStrategyResolver {

    private final Map<String, LocationStoreStrategy> strategies;
    private final LocationProperties properties;

    public LocationStoreStrategyResolver(List<LocationStoreStrategy> strategies, LocationProperties properties) {
        this.strategies = strategies.stream()
                .collect(Collectors.toMap(LocationStoreStrategy::name, Function.identity()));
        this.properties = properties;
    }

    public LocationStoreStrategy active() {
        LocationStoreStrategy strategy = strategies.get(properties.getBackend());
        if (strategy == null) {
            throw new IllegalStateException("No location backend for app.location.backend="
                    + properties.getBackend() + ". Available: " + strategies.keySet());
        }
        return strategy;
    }
}
