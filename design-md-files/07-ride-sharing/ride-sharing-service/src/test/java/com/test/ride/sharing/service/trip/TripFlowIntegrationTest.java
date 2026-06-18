package com.test.ride.sharing.service.trip;

import com.test.ride.sharing.service.config.DataInitializer;
import com.test.ride.sharing.service.location.LocationService;
import com.test.ride.sharing.service.matching.MatchingOrchestrator;
import com.test.ride.sharing.service.shared.GeoPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class TripFlowIntegrationTest {

    @Autowired
    private TripService tripService;

    @Autowired
    private LocationService locationService;

    @Autowired
    private com.test.ride.sharing.service.pricing.PricingService pricingService;

    @Autowired
    private MatchingOrchestrator matchingOrchestrator;

    @BeforeEach
    void driverOnline() {
        locationService.goOnline(
                DataInitializer.SEED_DRIVER_ID,
                DataInitializer.SEED_VEHICLE_ID,
                DataInitializer.BANGALORE_CITY_ID,
                new GeoPoint(new BigDecimal("12.9710"), new BigDecimal("77.5940"))
        );
    }

    @Test
    void fullTripLifecycleWithAutoOffer() throws InterruptedException {
        Map<String, Object> quote = pricingService.createFareEstimate(
                DataInitializer.SEED_RIDER_ID,
                new GeoPoint(new BigDecimal("12.9716"), new BigDecimal("77.5946")),
                new GeoPoint(new BigDecimal("12.9352"), new BigDecimal("77.6245")),
                com.test.ride.sharing.service.shared.VehicleType.ECONOMY,
                DataInitializer.BANGALORE_CITY_ID
        );

        UUID quoteId = UUID.fromString(quote.get("quote_id").toString());
        Map<String, Object> created = tripService.requestTrip(
                DataInitializer.SEED_RIDER_ID,
                quoteId,
                "MG Road, Bangalore",
                "Koramangala, Bangalore"
        );
        UUID tripId = UUID.fromString(created.get("trip_id").toString());
        assertThat(created.get("status")).isEqualTo("MATCHING");

        Thread.sleep(1500);
        assertThat(matchingOrchestrator.getPendingOfferForDriver(DataInitializer.SEED_DRIVER_ID)).isPresent();

        Map<String, Object> accepted = tripService.acceptTrip(DataInitializer.SEED_DRIVER_ID, tripId);
        assertThat(accepted.get("status")).isEqualTo("DRIVER_MATCHED");
        String otp = ((Map<?, ?>) accepted.get("rider")).get("otp").toString();

        tripService.arriveAtPickup(DataInitializer.SEED_DRIVER_ID, tripId);
        tripService.startTrip(DataInitializer.SEED_DRIVER_ID, tripId, otp);

        Map<String, Object> completed = tripService.completeTrip(
                DataInitializer.SEED_DRIVER_ID,
                tripId,
                new BigDecimal("12.9352"),
                new BigDecimal("77.6245")
        );
        assertThat(completed.get("status")).isEqualTo("COMPLETED");
    }
}
