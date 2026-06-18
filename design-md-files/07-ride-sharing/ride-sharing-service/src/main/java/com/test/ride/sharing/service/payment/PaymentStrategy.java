package com.test.ride.sharing.service.payment;

import com.test.ride.sharing.service.trip.Trip;

public interface PaymentStrategy {

    String name();

    Payment capture(Trip trip, String idempotencyKey);
}
