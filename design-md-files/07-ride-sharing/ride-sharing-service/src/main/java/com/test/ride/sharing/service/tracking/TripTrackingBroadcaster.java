package com.test.ride.sharing.service.tracking;

import com.test.ride.sharing.service.location.DriverPosition;
import com.test.ride.sharing.service.location.LocationService;
import com.test.ride.sharing.service.trip.Trip;
import com.test.ride.sharing.service.trip.TripRepository;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class TripTrackingBroadcaster {

    private final TripTrackingWebSocketHandler webSocketHandler;
    private final TripRepository tripRepository;
    private final LocationService locationService;

    public TripTrackingBroadcaster(TripTrackingWebSocketHandler webSocketHandler,
                                   TripRepository tripRepository,
                                   LocationService locationService) {
        this.webSocketHandler = webSocketHandler;
        this.tripRepository = tripRepository;
        this.locationService = locationService;
    }

    @EventListener
    public void onDriverLocationUpdated(DriverLocationUpdatedEvent event) {
        broadcastDriverLocation(event.driverId(), event.tripId());
    }

    public void broadcastDriverLocation(UUID driverId, UUID tripId) {
        DriverPosition position = locationService.getDriverPosition(driverId);
        if (position == null) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("driver_id", driverId.toString());
        data.put("lat", position.position().getLat());
        data.put("lng", position.position().getLng());
        data.put("heading", position.heading());
        data.put("timestamp", position.updatedAt().toString());
        webSocketHandler.broadcast(tripId, "DRIVER_LOCATION", data);
    }

    public void broadcastTripStatus(UUID tripId, String status, String otp) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("trip_id", tripId.toString());
        data.put("status", status);
        if (otp != null) {
            data.put("otp", otp);
        }
        webSocketHandler.broadcast(tripId, "TRIP_STATUS", data);
    }

    public void broadcastSyncState(UUID tripId) {
        tripRepository.findById(tripId).ifPresent(trip -> {
            broadcastTripStatus(tripId, trip.getStatus().name(),
                    "DRIVER_ARRIVED".equals(trip.getStatus().name()) ? trip.getOtp() : null);
            if (trip.getDriverId() != null) {
                broadcastDriverLocation(trip.getDriverId(), tripId);
            }
        });
    }
}
