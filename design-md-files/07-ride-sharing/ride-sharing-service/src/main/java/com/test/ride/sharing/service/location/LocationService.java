package com.test.ride.sharing.service.location;

import com.test.ride.sharing.service.identity.Driver;
import com.test.ride.sharing.service.identity.IdentityService;
import com.test.ride.sharing.service.identity.Vehicle;
import com.test.ride.sharing.service.shared.GeoPoint;
import com.test.ride.sharing.service.shared.VehicleType;
import com.test.ride.sharing.service.tracking.DriverLocationUpdatedEvent;
import com.test.ride.sharing.service.web.error.BusinessRuleException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class LocationService {

    private final LocationStoreStrategyResolver locationStoreResolver;
    private final IdentityService identityService;
    private final ApplicationEventPublisher eventPublisher;

    public LocationService(LocationStoreStrategyResolver locationStoreResolver,
                           IdentityService identityService,
                           ApplicationEventPublisher eventPublisher) {
        this.locationStoreResolver = locationStoreResolver;
        this.identityService = identityService;
        this.eventPublisher = eventPublisher;
    }

    private LocationStoreStrategy store() {
        return locationStoreResolver.active();
    }

    public void goOnline(UUID driverId, UUID vehicleId, UUID cityId, GeoPoint initialPosition) {
        Driver driver = identityService.getDriver(driverId);
        if (driver.getOnboardingStatus() != Driver.OnboardingStatus.APPROVED) {
            throw new BusinessRuleException("DRIVER_SUSPENDED", "Driver is not approved to go online");
        }
        Vehicle vehicle = identityService.getVehicle(vehicleId);
        if (!vehicle.getDriverId().equals(driverId)) {
            throw new BusinessRuleException("FORBIDDEN", "Vehicle does not belong to driver");
        }

        store().upsert(new DriverPosition(
                driverId, cityId, vehicleId, vehicle.getVehicleType(),
                initialPosition, 0, BigDecimal.ZERO, Instant.now(),
                DriverAvailabilityStatus.AVAILABLE, null
        ));
    }

    public void goOffline(UUID driverId) {
        store().remove(driverId);
    }

    public void updateLocation(UUID driverId, GeoPoint point, Integer heading, BigDecimal speedKmh) {
        DriverPosition existing = store().get(driverId);
        if (existing == null || existing.status() == DriverAvailabilityStatus.OFFLINE) {
            throw new BusinessRuleException("DRIVER_OFFLINE", "Driver must be online to update location");
        }
        DriverPosition updated = copy(existing, existing.status(), existing.currentTripId(), point, heading, speedKmh);
        store().upsert(updated);
        if (updated.currentTripId() != null) {
            eventPublisher.publishEvent(new DriverLocationUpdatedEvent(driverId, updated.currentTripId()));
        }
    }

    public void markOnTrip(UUID driverId, UUID tripId) {
        DriverPosition existing = requireOnline(driverId);
        store().upsert(copy(existing, DriverAvailabilityStatus.ON_TRIP, tripId,
                existing.position(), existing.heading(), existing.speedKmh()));
    }

    public void markAvailable(UUID driverId) {
        DriverPosition existing = store().get(driverId);
        if (existing == null) {
            return;
        }
        store().upsert(copy(existing, DriverAvailabilityStatus.AVAILABLE, null,
                existing.position(), existing.heading(), existing.speedKmh()));
    }

    public List<NearbyDriver> findNearbyDrivers(UUID cityId, GeoPoint center, double radiusKm,
                                                VehicleType vehicleType, int limit) {
        return store().findNearby(cityId, center, radiusKm, vehicleType, limit);
    }

    public DriverPosition getDriverPosition(UUID driverId) {
        return store().get(driverId);
    }

    public Map<String, Object> getAvailability(UUID driverId) {
        DriverPosition existing = store().get(driverId);
        if (existing == null) {
            return Map.of("status", "OFFLINE");
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", existing.status().name());
        response.put("city_id", existing.cityId());
        if (existing.currentTripId() != null) {
            response.put("current_trip_id", existing.currentTripId().toString());
        }
        return response;
    }

    private DriverPosition requireOnline(UUID driverId) {
        DriverPosition existing = store().get(driverId);
        if (existing == null) {
            throw new BusinessRuleException("DRIVER_OFFLINE", "Driver is offline");
        }
        return existing;
    }

    private DriverPosition copy(DriverPosition existing, DriverAvailabilityStatus status, UUID tripId,
                                GeoPoint position, Integer heading, BigDecimal speedKmh) {
        return new DriverPosition(
                existing.driverId(), existing.cityId(), existing.vehicleId(), existing.vehicleType(),
                position, heading, speedKmh, Instant.now(), status, tripId
        );
    }
}
