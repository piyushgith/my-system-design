package com.test.ride.sharing.service.location.web;

import com.test.ride.sharing.service.location.LocationService;
import com.test.ride.sharing.service.shared.GeoPoint;
import com.test.ride.sharing.service.shared.UserRole;
import com.test.ride.sharing.service.web.AuthContext;
import com.test.ride.sharing.service.web.AuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1/driver")
public class DriverLocationController {

    private final LocationService locationService;

    public DriverLocationController(LocationService locationService) {
        this.locationService = locationService;
    }

    @GetMapping("/availability")
    public Map<String, Object> getAvailability(HttpServletRequest httpRequest) {
        AuthContext auth = AuthInterceptor.requireRole(httpRequest, UserRole.DRIVER);
        return locationService.getAvailability(auth.userId());
    }

    @PostMapping("/availability/online")
    public Map<String, Object> goOnline(@Valid @RequestBody GoOnlineRequest request, HttpServletRequest httpRequest) {
        AuthContext auth = AuthInterceptor.requireRole(httpRequest, UserRole.DRIVER);
        GeoPoint position = new GeoPoint(request.lat(), request.lng());
        locationService.goOnline(auth.userId(), request.vehicleId(), request.cityId(), position);
        return Map.of("status", "AVAILABLE", "city_id", request.cityId(), "timestamp", Instant.now().toString());
    }

    @PostMapping("/availability/offline")
    public Map<String, Object> goOffline(HttpServletRequest httpRequest) {
        AuthContext auth = AuthInterceptor.requireRole(httpRequest, UserRole.DRIVER);
        locationService.goOffline(auth.userId());
        return Map.of("status", "OFFLINE", "timestamp", Instant.now().toString());
    }

    @PostMapping("/location")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateLocation(@Valid @RequestBody LocationUpdateRequest request, HttpServletRequest httpRequest) {
        AuthContext auth = AuthInterceptor.requireRole(httpRequest, UserRole.DRIVER);
        locationService.updateLocation(
                auth.userId(),
                new GeoPoint(request.lat(), request.lng()),
                request.heading(),
                request.speedKmh()
        );
    }

    public record GoOnlineRequest(
            @NotNull UUID vehicleId,
            @NotNull UUID cityId,
            @NotNull BigDecimal lat,
            @NotNull BigDecimal lng
    ) {
    }

    public record LocationUpdateRequest(
            @NotNull BigDecimal lat,
            @NotNull BigDecimal lng,
            Integer heading,
            BigDecimal speedKmh
    ) {
    }
}
