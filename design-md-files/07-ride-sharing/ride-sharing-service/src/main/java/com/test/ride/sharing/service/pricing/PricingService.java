package com.test.ride.sharing.service.pricing;

import com.test.ride.sharing.service.identity.City;
import com.test.ride.sharing.service.identity.IdentityService;
import com.test.ride.sharing.service.routing.RouteEstimate;
import com.test.ride.sharing.service.routing.RoutingStrategyResolver;
import com.test.ride.sharing.service.shared.GeoPoint;
import com.test.ride.sharing.service.shared.GeoUtils;
import com.test.ride.sharing.service.shared.VehicleType;
import com.test.ride.sharing.service.shared.Uuids;
import com.test.ride.sharing.service.web.error.BusinessRuleException;
import com.test.ride.sharing.service.web.error.ResourceNotFoundException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
@EnableConfigurationProperties(PricingProperties.class)
public class PricingService {

    private final FareQuoteRepository fareQuoteRepository;
    private final RoutingStrategyResolver routingStrategyResolver;
    private final StandardPricingStrategy pricingStrategy;
    private final PricingProperties pricingProperties;
    private final IdentityService identityService;

    public PricingService(FareQuoteRepository fareQuoteRepository,
                          RoutingStrategyResolver routingStrategyResolver,
                          StandardPricingStrategy pricingStrategy,
                          PricingProperties pricingProperties,
                          IdentityService identityService) {
        this.fareQuoteRepository = fareQuoteRepository;
        this.routingStrategyResolver = routingStrategyResolver;
        this.pricingStrategy = pricingStrategy;
        this.pricingProperties = pricingProperties;
        this.identityService = identityService;
    }

    @Transactional
    public Map<String, Object> createFareEstimate(UUID riderId, GeoPoint pickup, GeoPoint destination,
                                                  VehicleType vehicleType, UUID cityId) {
        identityService.getRider(riderId);
        City city = identityService.getCity(cityId);

        RouteEstimate route = routingStrategyResolver.active().estimate(pickup, destination);
        FareBreakdown breakdown = pricingStrategy.calculate(route, vehicleType, pricingProperties.getDefaultSurgeMultiplier());

        FareQuote quote = new FareQuote();
        quote.setQuoteId(Uuids.v7());
        quote.setRiderId(riderId);
        quote.setPickupLat(pickup.getLat());
        quote.setPickupLng(pickup.getLng());
        quote.setDestinationLat(destination.getLat());
        quote.setDestinationLng(destination.getLng());
        quote.setVehicleType(vehicleType);
        quote.setBaseFare(breakdown.baseFare());
        quote.setDistanceFare(breakdown.distanceFare());
        quote.setTimeFare(breakdown.timeFare());
        quote.setSurgeMultiplier(breakdown.surgeMultiplier());
        quote.setPlatformFee(breakdown.platformFee());
        quote.setTotalFareMin(breakdown.totalMin());
        quote.setTotalFareMax(breakdown.totalMax());
        quote.setCityId(city.getCityId());
        quote.setEstimatedDistanceKm(GeoUtils.toBigDecimal(route.distanceKm()));
        quote.setEstimatedDurationMin(route.durationMinutes());
        quote.setExpiresAt(Instant.now().plusSeconds(pricingProperties.getQuoteTtlMinutes() * 60L));
        fareQuoteRepository.save(quote);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("quote_id", quote.getQuoteId());
        response.put("vehicle_type", vehicleType.name());
        response.put("fare_min", quote.getTotalFareMin());
        response.put("fare_max", quote.getTotalFareMax());
        response.put("currency", "INR");
        response.put("surge_multiplier", quote.getSurgeMultiplier());
        response.put("surge_active", quote.getSurgeMultiplier().doubleValue() > 1.0);
        response.put("estimated_duration_min", quote.getEstimatedDurationMin());
        response.put("estimated_distance_km", quote.getEstimatedDistanceKm());
        response.put("expires_at", quote.getExpiresAt().toString());
        response.put("breakdown", Map.of(
                "base_fare", breakdown.baseFare(),
                "distance_fare", breakdown.distanceFare(),
                "time_fare", breakdown.timeFare(),
                "surge_premium", breakdown.surgePremium(),
                "platform_fee", breakdown.platformFee()
        ));
        return response;
    }

    @Transactional
    public FareQuote consumeQuote(UUID quoteId, UUID riderId) {
        FareQuote quote = fareQuoteRepository.findById(quoteId)
                .orElseThrow(() -> new ResourceNotFoundException("Quote not found: " + quoteId));
        if (!quote.getRiderId().equals(riderId)) {
            throw new BusinessRuleException("FORBIDDEN", "Quote does not belong to rider");
        }
        if (quote.isUsed()) {
            throw new BusinessRuleException("EXPIRED_QUOTE", "Fare quote already used");
        }
        if (quote.getExpiresAt().isBefore(Instant.now())) {
            throw new BusinessRuleException("EXPIRED_QUOTE", "Fare quote has expired");
        }
        quote.setUsed(true);
        return fareQuoteRepository.save(quote);
    }
}
