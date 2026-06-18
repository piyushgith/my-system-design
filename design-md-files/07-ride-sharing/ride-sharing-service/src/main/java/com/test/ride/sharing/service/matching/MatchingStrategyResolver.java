package com.test.ride.sharing.service.matching;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@EnableConfigurationProperties(MatchingProperties.class)
public class MatchingStrategyResolver {

    private final Map<String, MatchingStrategy> strategies;
    private final MatchingProperties properties;

    public MatchingStrategyResolver(List<MatchingStrategy> strategies, MatchingProperties properties) {
        this.strategies = strategies.stream()
                .collect(Collectors.toMap(MatchingStrategy::name, Function.identity()));
        this.properties = properties;
    }

    public MatchingStrategy active() {
        MatchingStrategy strategy = strategies.get(properties.getBackend());
        if (strategy == null) {
            throw new IllegalStateException("No matching backend for app.matching.backend="
                    + properties.getBackend() + ". Available: " + strategies.keySet());
        }
        return strategy;
    }

    public double searchRadiusKm() {
        return properties.getSearchRadiusKm();
    }
}
