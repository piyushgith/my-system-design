package com.test.ride.sharing.service.trip.web;

import com.test.ride.sharing.service.shared.IdempotencyService;
import com.test.ride.sharing.service.shared.UserRole;
import com.test.ride.sharing.service.trip.TripService;
import com.test.ride.sharing.service.web.AuthContext;
import com.test.ride.sharing.service.web.AuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1/driver/trips")
public class DriverTripController {

    private final TripService tripService;
    private final IdempotencyService idempotencyService;

    public DriverTripController(TripService tripService, IdempotencyService idempotencyService) {
        this.tripService = tripService;
        this.idempotencyService = idempotencyService;
    }

    @GetMapping("/offer")
    public Map<String, Object> currentOffer(HttpServletRequest httpRequest) {
        AuthContext auth = AuthInterceptor.requireRole(httpRequest, UserRole.DRIVER);
        return tripService.getCurrentOfferForDriver(auth.userId());
    }

    @GetMapping("/pending")
    public List<Map<String, Object>> pendingTrips(HttpServletRequest httpRequest) {
        AuthContext auth = AuthInterceptor.requireRole(httpRequest, UserRole.DRIVER);
        return tripService.listPendingTripsForDriver(auth.userId());
    }

    @PostMapping("/{tripId}/accept")
    public ResponseEntity<Object> acceptTrip(
            @PathVariable UUID tripId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest httpRequest) {
        return idempotencyService.findCached(idempotencyKey)
                .orElseGet(() -> {
                    AuthContext auth = AuthInterceptor.requireRole(httpRequest, UserRole.DRIVER);
                    Map<String, Object> body = tripService.acceptTrip(auth.userId(), tripId);
                    idempotencyService.store(idempotencyKey, HttpStatus.OK.value(), body);
                    return ResponseEntity.ok((Object) body);
                });
    }

    @PostMapping("/{tripId}/reject")
    public ResponseEntity<Void> rejectTrip(
            @PathVariable UUID tripId,
            @RequestBody(required = false) RejectTripRequest request,
            HttpServletRequest httpRequest) {
        AuthContext auth = AuthInterceptor.requireRole(httpRequest, UserRole.DRIVER);
        String reason = request == null ? null : request.reason();
        tripService.rejectTrip(auth.userId(), tripId, reason);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{tripId}/arrive")
    public ResponseEntity<Object> arrive(
            @PathVariable UUID tripId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest httpRequest) {
        return idempotencyService.findCached(idempotencyKey)
                .orElseGet(() -> {
                    AuthContext auth = AuthInterceptor.requireRole(httpRequest, UserRole.DRIVER);
                    Map<String, Object> body = tripService.arriveAtPickup(auth.userId(), tripId);
                    idempotencyService.store(idempotencyKey, HttpStatus.OK.value(), body);
                    return ResponseEntity.ok((Object) body);
                });
    }

    @PostMapping("/{tripId}/start")
    public ResponseEntity<Object> startTrip(
            @PathVariable UUID tripId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody StartTripRequest request,
            HttpServletRequest httpRequest) {
        return idempotencyService.findCached(idempotencyKey)
                .orElseGet(() -> {
                    AuthContext auth = AuthInterceptor.requireRole(httpRequest, UserRole.DRIVER);
                    Map<String, Object> body = tripService.startTrip(auth.userId(), tripId, request.otp());
                    idempotencyService.store(idempotencyKey, HttpStatus.OK.value(), body);
                    return ResponseEntity.ok((Object) body);
                });
    }

    @PostMapping("/{tripId}/complete")
    public ResponseEntity<Object> completeTrip(
            @PathVariable UUID tripId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CompleteTripRequest request,
            HttpServletRequest httpRequest) {
        return idempotencyService.findCached(idempotencyKey)
                .orElseGet(() -> {
                    AuthContext auth = AuthInterceptor.requireRole(httpRequest, UserRole.DRIVER);
                    Map<String, Object> body = tripService.completeTrip(
                            auth.userId(), tripId, request.finalLat(), request.finalLng());
                    idempotencyService.store(idempotencyKey, HttpStatus.OK.value(), body);
                    return ResponseEntity.ok((Object) body);
                });
    }

    public record RejectTripRequest(String reason) {
    }

    public record StartTripRequest(@NotBlank String otp) {
    }

    public record CompleteTripRequest(@NotNull BigDecimal finalLat, @NotNull BigDecimal finalLng) {
    }
}
