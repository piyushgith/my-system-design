package com.test.ride.sharing.service.pricing.web;

import com.test.ride.sharing.service.pricing.PricingService;
import com.test.ride.sharing.service.shared.GeoPoint;
import com.test.ride.sharing.service.shared.UserRole;
import com.test.ride.sharing.service.shared.VehicleType;
import com.test.ride.sharing.service.web.AuthContext;
import com.test.ride.sharing.service.web.AuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1/fare-estimates")
public class FareEstimateController {

    private final PricingService pricingService;

    public FareEstimateController(PricingService pricingService) {
        this.pricingService = pricingService;
    }

    @PostMapping
    public Map<String, Object> estimate(@Valid @RequestBody FareEstimateRequest request, HttpServletRequest httpRequest) {
        AuthContext auth = AuthInterceptor.requireRole(httpRequest, UserRole.RIDER);
        GeoPoint pickup = new GeoPoint(request.pickupLat(), request.pickupLng());
        GeoPoint destination = new GeoPoint(request.destinationLat(), request.destinationLng());
        return pricingService.createFareEstimate(auth.userId(), pickup, destination, request.vehicleType(),
                request.cityId());
    }

    public record FareEstimateRequest(
            @NotNull BigDecimal pickupLat,
            @NotNull BigDecimal pickupLng,
            @NotNull BigDecimal destinationLat,
            @NotNull BigDecimal destinationLng,
            @NotNull VehicleType vehicleType,
            @NotNull UUID cityId
    ) {
    }
}
