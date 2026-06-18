package com.test.ride.sharing.service.routing;

import com.test.ride.sharing.service.shared.GeoPoint;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Component
public class OsrmRoutingStrategy implements RoutingStrategy {

    private final RoutingProperties properties;
    private final RestClient restClient;

    public OsrmRoutingStrategy(RoutingProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.create(properties.getOsrm().getBaseUrl());
    }

    @Override
    public String name() {
        return "osrm";
    }

    @Override
    @SuppressWarnings("unchecked")
    public RouteEstimate estimate(GeoPoint origin, GeoPoint destination) {
        String path = String.format("/route/v1/driving/%s,%s;%s,%s?overview=false",
                origin.getLng(), origin.getLat(),
                destination.getLng(), destination.getLat());

        Map<String, Object> response = restClient.get().uri(path).retrieve().body(Map.class);
        if (response == null) {
            throw new RoutingException("OSRM returned empty response");
        }

        List<Map<String, Object>> routes = (List<Map<String, Object>>) response.get("routes");
        if (routes == null || routes.isEmpty()) {
            throw new RoutingException("OSRM returned no route");
        }

        Map<String, Object> route = routes.getFirst();
        double distanceKm = ((Number) route.get("distance")).doubleValue() / 1000.0;
        int durationMinutes = (int) Math.max(1, Math.ceil(((Number) route.get("duration")).doubleValue() / 60.0));
        return new RouteEstimate(distanceKm, durationMinutes);
    }
}
