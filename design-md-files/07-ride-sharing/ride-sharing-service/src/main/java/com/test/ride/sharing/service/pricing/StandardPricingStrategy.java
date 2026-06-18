package com.test.ride.sharing.service.pricing;

import com.test.ride.sharing.service.routing.RouteEstimate;
import com.test.ride.sharing.service.shared.VehicleType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
public class StandardPricingStrategy implements PricingStrategy {

    private final PricingProperties properties;

    public StandardPricingStrategy(PricingProperties properties) {
        this.properties = properties;
    }

    @Override
    public String name() {
        return "standard";
    }

    @Override
    public FareBreakdown calculate(RouteEstimate route, VehicleType vehicleType, BigDecimal surgeMultiplier) {
        int distanceFare = (int) Math.ceil(route.distanceKm() * properties.getPerKmRate());
        int timeFare = route.durationMinutes() * properties.getPerMinRate();
        int subtotal = properties.getBaseFare() + distanceFare + timeFare;
        int surgePremium = surgeMultiplier.subtract(BigDecimal.ONE)
                .max(BigDecimal.ZERO)
                .multiply(BigDecimal.valueOf(subtotal))
                .setScale(0, RoundingMode.HALF_UP)
                .intValue();
        int totalBeforeFee = subtotal + surgePremium;
        int totalMin = Math.max(properties.getMinimumFare(), totalBeforeFee);
        int totalMax = totalMin + Math.max(10, (int) (route.distanceKm() * 2));
        return new FareBreakdown(
                properties.getBaseFare(),
                distanceFare,
                timeFare,
                surgePremium,
                properties.getPlatformFee(),
                totalMin,
                totalMax,
                surgeMultiplier
        );
    }
}
