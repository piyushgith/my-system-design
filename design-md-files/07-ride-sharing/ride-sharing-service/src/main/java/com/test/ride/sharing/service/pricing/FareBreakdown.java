package com.test.ride.sharing.service.pricing;

import com.test.ride.sharing.service.routing.RouteEstimate;

import java.math.BigDecimal;

public record FareBreakdown(
        int baseFare,
        int distanceFare,
        int timeFare,
        int surgePremium,
        int platformFee,
        int totalMin,
        int totalMax,
        BigDecimal surgeMultiplier
) {
}
