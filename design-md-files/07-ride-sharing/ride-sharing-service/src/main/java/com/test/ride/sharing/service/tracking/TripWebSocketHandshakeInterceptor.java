package com.test.ride.sharing.service.tracking;

import com.test.ride.sharing.service.shared.UserRole;
import com.test.ride.sharing.service.trip.Trip;
import com.test.ride.sharing.service.trip.TripRepository;
import com.test.ride.sharing.service.web.DevAuthSupport;
import com.test.ride.sharing.service.web.error.UnauthorizedException;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;
import java.util.UUID;

@Component
public class TripWebSocketHandshakeInterceptor implements HandshakeInterceptor {

    public static final String TRIP_ID_ATTR = "tripId";

    private final TripRepository tripRepository;

    public TripWebSocketHandshakeInterceptor(TripRepository tripRepository) {
        this.tripRepository = tripRepository;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String path = request.getURI().getPath();
        UUID tripId = extractTripId(path);
        if (tripId == null) {
            return false;
        }

        DevAuthSupport.ResolvedAuth auth;
        try {
            auth = DevAuthSupport.resolve(request.getHeaders());
        } catch (UnauthorizedException ex) {
            return false;
        }

        UUID userId = auth.userId();
        UserRole role = auth.role();
        Trip trip = tripRepository.findById(tripId).orElse(null);
        if (trip == null) {
            return false;
        }
        if (role == UserRole.RIDER && !trip.getRiderId().equals(userId)) {
            return false;
        }
        if (role == UserRole.DRIVER && trip.getDriverId() != null && !trip.getDriverId().equals(userId)) {
            return false;
        }

        attributes.put(TRIP_ID_ATTR, tripId);
        attributes.put("userId", userId);
        attributes.put("role", role);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }

    private UUID extractTripId(String path) {
        // /v1/trips/{tripId}/stream
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if ("trips".equals(parts[i]) && i + 1 < parts.length) {
                try {
                    return UUID.fromString(parts[i + 1]);
                } catch (IllegalArgumentException ex) {
                    return null;
                }
            }
        }
        return null;
    }
}
