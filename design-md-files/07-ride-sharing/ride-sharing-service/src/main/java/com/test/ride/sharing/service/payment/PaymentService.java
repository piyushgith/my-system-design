package com.test.ride.sharing.service.payment;

import com.test.ride.sharing.service.trip.Trip;
import org.springframework.stereotype.Service;

@Service
public class PaymentService {

    private final PaymentStrategyResolver paymentStrategyResolver;

    public PaymentService(PaymentStrategyResolver paymentStrategyResolver) {
        this.paymentStrategyResolver = paymentStrategyResolver;
    }

    public Payment captureTripPayment(Trip trip, String idempotencyKey) {
        return paymentStrategyResolver.active().capture(trip, idempotencyKey);
    }
}
