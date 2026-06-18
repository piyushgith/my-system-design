package com.test.ride.sharing.service.payment;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@EnableConfigurationProperties(PaymentProperties.class)
public class PaymentStrategyResolver {

    private final Map<String, PaymentStrategy> strategies;
    private final PaymentProperties properties;

    public PaymentStrategyResolver(List<PaymentStrategy> strategies, PaymentProperties properties) {
        this.strategies = strategies.stream()
                .collect(Collectors.toMap(PaymentStrategy::name, Function.identity()));
        this.properties = properties;
    }

    public PaymentStrategy active() {
        PaymentStrategy strategy = strategies.get(properties.getBackend());
        if (strategy == null) {
            throw new IllegalStateException("No payment backend for app.payment.backend="
                    + properties.getBackend() + ". Available: " + strategies.keySet());
        }
        return strategy;
    }
}
