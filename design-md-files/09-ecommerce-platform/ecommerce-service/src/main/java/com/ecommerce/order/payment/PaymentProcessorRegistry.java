package com.ecommerce.order.payment;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Resolves a {@link PaymentProcessor} by method identifier. */
@Component
public class PaymentProcessorRegistry {

    private final Map<String, PaymentProcessor> byMethod;

    public PaymentProcessorRegistry(List<PaymentProcessor> processors) {
        this.byMethod = processors.stream()
                .collect(Collectors.toMap(p -> p.method().toUpperCase(), Function.identity()));
    }

    public PaymentProcessor forMethod(String method) {
        PaymentProcessor processor = byMethod.get(method == null ? null : method.toUpperCase());
        if (processor == null) {
            throw new IllegalArgumentException("Unsupported payment method: " + method);
        }
        return processor;
    }
}
