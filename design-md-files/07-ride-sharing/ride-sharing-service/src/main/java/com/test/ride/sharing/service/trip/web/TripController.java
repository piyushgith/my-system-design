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

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1/trips")
public class TripController {

    private final TripService tripService;
    private final IdempotencyService idempotencyService;

    public TripController(TripService tripService, IdempotencyService idempotencyService) {
        this.tripService = tripService;
        this.idempotencyService = idempotencyService;
    }

    @PostMapping
    public ResponseEntity<Object> createTrip(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateTripRequest request,
            HttpServletRequest httpRequest) {
        return idempotencyService.findCached(idempotencyKey)
                .orElseGet(() -> {
                    AuthContext auth = AuthInterceptor.requireRole(httpRequest, UserRole.RIDER);
                    Map<String, Object> body = tripService.requestTrip(
                            auth.userId(),
                            request.quoteId(),
                            request.pickupAddress(),
                            request.destinationAddress()
                    );
                    idempotencyService.store(idempotencyKey, HttpStatus.CREATED.value(), body);
                    return ResponseEntity.status(HttpStatus.CREATED).body((Object) body);
                });
    }

    @GetMapping("/{tripId}")
    public Map<String, Object> getTrip(@PathVariable UUID tripId, HttpServletRequest httpRequest) {
        AuthContext auth = AuthInterceptor.requireAuth(httpRequest);
        return tripService.getTripDetails(auth.userId(), auth.role().name(), tripId);
    }

    @PostMapping("/{tripId}/cancel")
    public ResponseEntity<Object> cancelTrip(
            @PathVariable UUID tripId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) CancelTripRequest request,
            HttpServletRequest httpRequest) {
        return idempotencyService.findCached(idempotencyKey)
                .orElseGet(() -> {
                    AuthContext auth = AuthInterceptor.requireRole(httpRequest, UserRole.RIDER);
                    String reason = request == null ? null : request.reason();
                    Map<String, Object> body = tripService.cancelTrip(auth.userId(), tripId, reason);
                    idempotencyService.store(idempotencyKey, HttpStatus.OK.value(), body);
                    return ResponseEntity.ok((Object) body);
                });
    }

    @PostMapping("/{tripId}/ratings")
    public Map<String, Object> rateTrip(
            @PathVariable UUID tripId,
            @Valid @RequestBody RateTripRequest request,
            HttpServletRequest httpRequest) {
        AuthContext auth = AuthInterceptor.requireRole(httpRequest, UserRole.RIDER);
        return tripService.rateTrip(auth.userId(), tripId, request.score(), request.comment());
    }

    public record CreateTripRequest(
            @NotNull UUID quoteId,
            @NotBlank String pickupAddress,
            @NotBlank String destinationAddress
    ) {
    }

    public record CancelTripRequest(String reason) {
    }

    public record RateTripRequest(@NotNull Integer score, String comment) {
    }
}
