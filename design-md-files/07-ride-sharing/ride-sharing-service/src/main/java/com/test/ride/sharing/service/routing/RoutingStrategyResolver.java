package com.test.ride.sharing.service.routing;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@EnableConfigurationProperties(RoutingProperties.class)
public class RoutingStrategyResolver {

    private final Map<String, RoutingStrategy> strategies;
    private final RoutingProperties properties;

    public RoutingStrategyResolver(List<RoutingStrategy> strategies, RoutingProperties properties) {
        this.strategies = strategies.stream()
                .collect(Collectors.toMap(RoutingStrategy::name, Function.identity()));
        this.properties = properties;
    }

    public RoutingStrategy active() {
        RoutingStrategy strategy = strategies.get(properties.getBackend());
        if (strategy == null) {
            throw new RoutingException("No routing backend registered for app.routing.backend="
                    + properties.getBackend() + ". Available: " + strategies.keySet());
        }
        return strategy;
    }
}
