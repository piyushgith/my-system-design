package com.test.ride.sharing.service.payment;

import com.test.ride.sharing.service.shared.Uuids;
import com.test.ride.sharing.service.trip.Trip;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class MockRazorpayPaymentStrategy implements PaymentStrategy {

    private static final Logger log = LoggerFactory.getLogger(MockRazorpayPaymentStrategy.class);

    private final PaymentRepository paymentRepository;

    public MockRazorpayPaymentStrategy(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    @Override
    public String name() {
        return "mock-razorpay";
    }

    @Override
    public Payment capture(Trip trip, String idempotencyKey) {
        return paymentRepository.findByTripId(trip.getTripId()).orElseGet(() -> {
            log.info("[mock-razorpay] capturing {} INR for trip {} rider {}",
                    trip.getFinalFare(), trip.getTripId(), trip.getRiderId());

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
            payment.setGatewayTransactionId("rzp_mock_" + Uuids.v7().toString().substring(0, 8));
            payment.setCapturedAt(Instant.now());
            return paymentRepository.save(payment);
        });
    }
}
