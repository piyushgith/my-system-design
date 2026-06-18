package com.test.ride.sharing.service.matching;

import java.util.UUID;

public record TripMatchingRequestedEvent(UUID tripId) {
}
