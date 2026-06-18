package com.test.ride.sharing.service.payment;

import com.test.ride.sharing.service.trip.Trip;
import com.test.ride.sharing.service.shared.Uuids;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class MockCashPaymentStrategy implements PaymentStrategy {

    private final PaymentRepository paymentRepository;

    public MockCashPaymentStrategy(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    @Override
    public String name() {
        return "cash";
    }

    @Override
    public Payment capture(Trip trip, String idempotencyKey) {
        return paymentRepository.findByTripId(trip.getTripId()).orElseGet(() -> {
            int amount = trip.getFinalFare();
            int platformCommission = (int) Math.round(amount * 0.2);
            int driverShare = amount - platformCommission;

            Payment payment = new Payment();
            payment.setPaymentId(Uuids.v7());
            payment.setTripId(trip.getTripId());
            payment.setRiderId(trip.getRiderId());
            payment.setDriverId(trip.getDriverId());
            payment.setAmount(amount);
            payment.setDriverShare(driverShare);
            payment.setPlatformCommission(platformCommission);
            payment.setStatus(PaymentStatus.CAPTURED);
            payment.setIdempotencyKey(idempotencyKey);
            payment.setCapturedAt(Instant.now());
            return paymentRepository.save(payment);
        });
    }
}
