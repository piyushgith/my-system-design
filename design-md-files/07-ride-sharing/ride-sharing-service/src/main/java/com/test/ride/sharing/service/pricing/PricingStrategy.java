package com.test.ride.sharing.service.pricing;

import com.test.ride.sharing.service.routing.RouteEstimate;
import com.test.ride.sharing.service.shared.VehicleType;

import java.math.BigDecimal;

public interface PricingStrategy {

    String name();

    FareBreakdown calculate(RouteEstimate route, VehicleType vehicleType, BigDecimal surgeMultiplier);
}
