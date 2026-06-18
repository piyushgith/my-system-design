package com.test.ride.sharing.service.trip;

import com.test.ride.sharing.service.identity.Driver;
import com.test.ride.sharing.service.identity.IdentityService;
import com.test.ride.sharing.service.identity.Rider;
import com.test.ride.sharing.service.identity.Vehicle;
import com.test.ride.sharing.service.location.LocationService;
import com.test.ride.sharing.service.location.NearbyDriver;
import com.test.ride.sharing.service.event.TripEventBus;
import com.test.ride.sharing.service.matching.MatchingOrchestrator;
import com.test.ride.sharing.service.matching.TripMatchingRequestedEvent;
import com.test.ride.sharing.service.notification.NotificationStrategyResolver;
import com.test.ride.sharing.service.tracking.TripTrackingBroadcaster;
import com.test.ride.sharing.service.payment.Payment;
import com.test.ride.sharing.service.payment.PaymentService;
import com.test.ride.sharing.service.pricing.FareQuote;
import com.test.ride.sharing.service.pricing.PricingService;
import com.test.ride.sharing.service.rating.Rating;
import com.test.ride.sharing.service.rating.RatingRepository;
import com.test.ride.sharing.service.shared.GeoPoint;
import com.test.ride.sharing.service.shared.GeoUtils;
import com.test.ride.sharing.service.shared.Uuids;
import com.test.ride.sharing.service.web.error.BusinessRuleException;
import com.test.ride.sharing.service.web.error.ResourceNotFoundException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@EnableConfigurationProperties(TripProperties.class)
public class TripService {

    private final TripRepository tripRepository;
    private final TripEventRepository tripEventRepository;
    private final PricingService pricingService;
    private final LocationService locationService;
    private final MatchingOrchestrator matchingOrchestrator;
    private final IdentityService identityService;
    private final PaymentService paymentService;
    private final RatingRepository ratingRepository;
    private final TripProperties tripProperties;
    private final TripEventBus tripEventBus;
    private final NotificationStrategyResolver notificationStrategyResolver;
    private final TripTrackingBroadcaster tripTrackingBroadcaster;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final SecureRandom secureRandom = new SecureRandom();

    public TripService(TripRepository tripRepository,
                       TripEventRepository tripEventRepository,
                       PricingService pricingService,
                       LocationService locationService,
                       MatchingOrchestrator matchingOrchestrator,
                       IdentityService identityService,
                       PaymentService paymentService,
                       RatingRepository ratingRepository,
                       TripProperties tripProperties,
                       TripEventBus tripEventBus,
                       NotificationStrategyResolver notificationStrategyResolver,
                       TripTrackingBroadcaster tripTrackingBroadcaster,
                       ApplicationEventPublisher applicationEventPublisher) {
        this.tripRepository = tripRepository;
        this.tripEventRepository = tripEventRepository;
        this.pricingService = pricingService;
        this.locationService = locationService;
        this.matchingOrchestrator = matchingOrchestrator;
        this.identityService = identityService;
        this.paymentService = paymentService;
        this.ratingRepository = ratingRepository;
        this.tripProperties = tripProperties;
        this.tripEventBus = tripEventBus;
        this.notificationStrategyResolver = notificationStrategyResolver;
        this.tripTrackingBroadcaster = tripTrackingBroadcaster;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    @Transactional
    public Map<String, Object> requestTrip(UUID riderId, UUID quoteId, String pickupAddress, String destinationAddress) {
        tripRepository.findActiveByRiderId(riderId).ifPresent(t -> {
            throw new BusinessRuleException(
                    "TRIP_ALREADY_ACTIVE",
                    "You already have an active trip in progress",
                    Map.of("trip_id", t.getTripId().toString())
            );
        });

        FareQuote quote = pricingService.consumeQuote(quoteId, riderId);

        Trip trip = new Trip();
        trip.setTripId(Uuids.v7());
        trip.setRiderId(riderId);
        trip.setStatus(TripStatus.REQUESTED);
        trip.setVehicleTypeRequested(quote.getVehicleType());
        trip.setPickupLat(quote.getPickupLat());
        trip.setPickupLng(quote.getPickupLng());
        trip.setPickupAddress(pickupAddress);
        trip.setDestinationLat(quote.getDestinationLat());
        trip.setDestinationLng(quote.getDestinationLng());
        trip.setDestinationAddress(destinationAddress);
        trip.setEstimatedDistanceKm(quote.getEstimatedDistanceKm());
        trip.setEstimatedDurationMin(quote.getEstimatedDurationMin());
        trip.setEstimatedFareMin(quote.getTotalFareMin());
        trip.setEstimatedFareMax(quote.getTotalFareMax());
        trip.setSurgeMultiplier(quote.getSurgeMultiplier());
        trip.setCityId(quote.getCityId());
        trip.setOtp(generateOtp());
        tripRepository.save(trip);
        recordEvent(trip, TripStatus.REQUESTED.name(), TripStatus.REQUESTED.name(), riderId, "RIDER");
        beginMatching(trip);
        applicationEventPublisher.publishEvent(new TripMatchingRequestedEvent(trip.getTripId()));
        tripEventBus.publish("TRIP_REQUESTED", trip.getTripId(), riderId, null, "{}");
        return toTripResponse(trip);
    }

    @Transactional
    public void beginMatching(Trip trip) {
        transition(trip, TripStatus.MATCHING, trip.getRiderId(), "SYSTEM");
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getCurrentOfferForDriver(UUID driverId) {
        return matchingOrchestrator.getPendingOfferForDriver(driverId)
                .map(offer -> {
                    Trip trip = getTrip(offer.getTripId());
                    return Map.<String, Object>of(
                            "offer_id", offer.getOfferId(),
                            "trip_id", offer.getTripId(),
                            "expires_at", offer.getExpiresAt().toString(),
                            "pickup_address", trip.getPickupAddress(),
                            "destination_address", trip.getDestinationAddress(),
                            "estimated_fare_min", trip.getEstimatedFareMin()
                    );
                })
                .orElseGet(() -> {
                    Map<String, Object> empty = new LinkedHashMap<>();
                    empty.put("offer", null);
                    return empty;
                });
    }

    @Transactional
    public Map<String, Object> acceptTrip(UUID driverId, UUID tripId) {
        tripRepository.findActiveByDriverId(driverId).ifPresent(t -> {
            throw new BusinessRuleException("DRIVER_ALREADY_MATCHED", "Driver already on another trip");
        });

        matchingOrchestrator.validateAcceptOffer(tripId, driverId);

        Trip trip = getTripForDriver(tripId, driverId);
        if (trip.getStatus() != TripStatus.MATCHING) {
            throw new BusinessRuleException("INVALID_STATE_TRANSITION", "Trip is not available for acceptance");
        }

        Vehicle vehicle = identityService.getActiveVehicle(driverId);
        trip.setDriverId(driverId);
        trip.setVehicleId(vehicle.getVehicleId());
        trip.setMatchedAt(Instant.now());
        matchingOrchestrator.markOfferAccepted(tripId, driverId);
        transition(trip, TripStatus.DRIVER_MATCHED, driverId, "DRIVER");
        locationService.markOnTrip(driverId, tripId);

        notificationStrategyResolver.notifyUser(
                trip.getRiderId(),
                "Driver matched",
                "Your driver is on the way",
                "/v1/trips/" + tripId
        );
        tripEventBus.publish("TRIP_MATCHED", tripId, trip.getRiderId(), driverId, "{}");

        return toDriverAcceptResponse(trip);
    }

    @Transactional
    public void rejectTrip(UUID driverId, UUID tripId, String reason) {
        Trip trip = getTrip(tripId);
        if (trip.getStatus() != TripStatus.MATCHING) {
            throw new BusinessRuleException("INVALID_STATE_TRANSITION", "Trip is not in matching state");
        }
        matchingOrchestrator.markOfferRejected(tripId, driverId);
        recordEvent(trip, trip.getStatus().name(), "REJECTED", driverId, "DRIVER");
    }

    @Transactional
    public Map<String, Object> arriveAtPickup(UUID driverId, UUID tripId) {
        Trip trip = getTripForDriverAction(driverId, tripId, TripStatus.DRIVER_MATCHED);
        trip.setDriverArrivedAt(Instant.now());
        transition(trip, TripStatus.DRIVER_ARRIVED, driverId, "DRIVER");
        return Map.of("trip_id", trip.getTripId(), "status", trip.getStatus().name(),
                "wait_time_started_at", trip.getDriverArrivedAt().toString());
    }

    @Transactional
    public Map<String, Object> startTrip(UUID driverId, UUID tripId, String otp) {
        Trip trip = getTripForDriverAction(driverId, tripId, TripStatus.DRIVER_ARRIVED);
        if (!trip.getOtp().equals(otp)) {
            throw new BusinessRuleException("INVALID_OTP", "OTP does not match; trip start denied");
        }
        trip.setTripStartedAt(Instant.now());
        transition(trip, TripStatus.IN_PROGRESS, driverId, "DRIVER");
        return Map.of(
                "trip_id", trip.getTripId(),
                "status", trip.getStatus().name(),
                "destination", Map.of(
                        "lat", trip.getDestinationLat(),
                        "lng", trip.getDestinationLng(),
                        "address", trip.getDestinationAddress()
                ),
                "navigation_url", buildNavigationUrl(trip.getDestinationLat(), trip.getDestinationLng())
        );
    }

    @Transactional
    public Map<String, Object> completeTrip(UUID driverId, UUID tripId, BigDecimal finalLat, BigDecimal finalLng) {
        Trip trip = getTripForDriverAction(driverId, tripId, TripStatus.IN_PROGRESS);
        Instant endedAt = Instant.now();
        trip.setTripEndedAt(endedAt);
        trip.setActualDurationMin((int) Math.max(1, Duration.between(trip.getTripStartedAt(), endedAt).toMinutes()));

        GeoPoint start = new GeoPoint(trip.getPickupLat(), trip.getPickupLng());
        GeoPoint end = new GeoPoint(finalLat, finalLng);
        trip.setActualDistanceKm(GeoUtils.toBigDecimal(GeoUtils.distanceKm(start, end)));

        int finalFare = Math.max(trip.getEstimatedFareMin(),
                trip.getEstimatedFareMin() + (trip.getActualDurationMin() - trip.getEstimatedDurationMin()));
        trip.setFinalFare(finalFare);

        transition(trip, TripStatus.COMPLETED, driverId, "DRIVER");
        locationService.markAvailable(driverId);

        Payment payment = paymentService.captureTripPayment(trip, "trip-complete-" + tripId);
        trip.setPaymentId(payment.getPaymentId());
        tripRepository.save(trip);

        tripEventBus.publish("PAYMENT_CAPTURED", tripId, trip.getRiderId(), driverId,
                "{\"amount\":" + payment.getAmount() + ",\"gateway\":\"" + payment.getGatewayTransactionId() + "\"}");
        notificationStrategyResolver.notifyUser(trip.getRiderId(), "Trip completed",
                "Paid INR " + payment.getAmount(), "/v1/trips/" + tripId);

        return Map.of(
                "trip_id", trip.getTripId(),
                "status", trip.getStatus().name(),
                "fare", Map.of(
                        "total", payment.getAmount(),
                        "driver_share", payment.getDriverShare(),
                        "platform_fee", payment.getPlatformCommission()
                ),
                "distance_km", trip.getActualDistanceKm(),
                "duration_min", trip.getActualDurationMin()
        );
    }

    @Transactional
    public Map<String, Object> cancelTrip(UUID riderId, UUID tripId, String reason) {
        Trip trip = getTripForRider(riderId, tripId);
        if (trip.getStatus() == TripStatus.COMPLETED || trip.getStatus() == TripStatus.CANCELLED) {
            throw new BusinessRuleException("INVALID_STATE_TRANSITION", "Trip cannot be cancelled");
        }

        int fee = switch (trip.getStatus()) {
            case DRIVER_MATCHED -> tripProperties.getCancellationFeeDispatched();
            case DRIVER_ARRIVED -> tripProperties.getCancellationFeeArrived();
            default -> 0;
        };

        trip.setCancellationReason(reason);
        trip.setCancelledBy("RIDER");
        trip.setCancellationFee(fee);
        trip.setCancelledAt(Instant.now());
        transition(trip, TripStatus.CANCELLED, riderId, "RIDER");

        if (trip.getDriverId() != null) {
            locationService.markAvailable(trip.getDriverId());
        }
        tripEventBus.publish("TRIP_CANCELLED", tripId, riderId, trip.getDriverId(), "{\"reason\":\"" + reason + "\"}");

        return Map.of(
                "trip_id", trip.getTripId(),
                "status", trip.getStatus().name(),
                "cancellation_fee", fee,
                "reason", reason == null ? "CHANGED_MIND" : reason
        );
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getTripDetails(UUID requesterId, String requesterRole, UUID tripId) {
        Trip trip = getTrip(tripId);
        if ("RIDER".equals(requesterRole) && !trip.getRiderId().equals(requesterId)) {
            throw new ResourceNotFoundException("Trip not found: " + tripId);
        }
        if ("DRIVER".equals(requesterRole) && trip.getDriverId() != null && !trip.getDriverId().equals(requesterId)) {
            throw new ResourceNotFoundException("Trip not found: " + tripId);
        }
        return toDetailedTripResponse(trip);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getActiveTripForRider(UUID riderId) {
        Map<String, Object> response = new LinkedHashMap<>();
        tripRepository.findActiveByRiderId(riderId).ifPresentOrElse(
                trip -> {
                    response.put("trip_id", trip.getTripId());
                    response.put("status", trip.getStatus().name());
                },
                () -> response.put("trip", null)
        );
        return response;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> listRiderTrips(UUID riderId, int limit) {
        Page<Trip> page = tripRepository.findByRiderIdOrderByRequestedAtDesc(riderId, PageRequest.of(0, limit));
        List<Map<String, Object>> trips = page.getContent().stream().map(this::toTripSummary).toList();
        Map<String, Object> pagination = new LinkedHashMap<>();
        pagination.put("limit", limit);
        pagination.put("has_more", page.hasNext());
        pagination.put("next_cursor", null);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("trips", trips);
        response.put("pagination", pagination);
        return response;
    }

    @Transactional
    public Map<String, Object> rateTrip(UUID riderId, UUID tripId, int score, String comment) {
        Trip trip = getTripForRider(riderId, tripId);
        if (trip.getStatus() != TripStatus.COMPLETED) {
            throw new BusinessRuleException("INVALID_STATE_TRANSITION", "Can only rate completed trips");
        }
        if (trip.getTripEndedAt() != null && trip.getTripEndedAt().isBefore(Instant.now().minus(Duration.ofHours(24)))) {
            throw new BusinessRuleException("INVALID_STATE_TRANSITION", "Rating window expired");
        }
        ratingRepository.findByTripIdAndRatedBy(tripId, "RIDER").ifPresent(r -> {
            throw new BusinessRuleException("INVALID_STATE_TRANSITION", "Trip already rated");
        });

        Rating rating = new Rating();
        rating.setRatingId(Uuids.v7());
        rating.setTripId(tripId);
        rating.setRatedBy("RIDER");
        rating.setRatedEntityId(trip.getDriverId());
        rating.setScore(score);
        rating.setComment(comment);
        ratingRepository.save(rating);

        return Map.of("rating_id", rating.getRatingId(), "score", score, "trip_id", tripId);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listPendingTripsForDriver(UUID driverId) {
        Driver driver = identityService.getDriver(driverId);
        return tripRepository.findByStatusAndCityId(TripStatus.MATCHING, driver.getCityId()).stream()
                .map(this::toTripSummary)
                .toList();
    }

    private Trip getTrip(UUID tripId) {
        return tripRepository.findById(tripId)
                .orElseThrow(() -> new ResourceNotFoundException("Trip not found: " + tripId));
    }

    private Trip getTripForRider(UUID riderId, UUID tripId) {
        Trip trip = getTrip(tripId);
        if (!trip.getRiderId().equals(riderId)) {
            throw new ResourceNotFoundException("Trip not found: " + tripId);
        }
        return trip;
    }

    private Trip getTripForDriver(UUID tripId, UUID driverId) {
        Trip trip = getTrip(tripId);
        Driver driver = identityService.getDriver(driverId);
        if (!trip.getCityId().equals(driver.getCityId())) {
            throw new BusinessRuleException("FORBIDDEN", "Trip is outside driver city");
        }
        return trip;
    }

    private Trip getTripForDriverAction(UUID driverId, UUID tripId, TripStatus expectedStatus) {
        Trip trip = getTrip(tripId);
        if (trip.getDriverId() == null || !trip.getDriverId().equals(driverId)) {
            throw new ResourceNotFoundException("Trip not found: " + tripId);
        }
        if (trip.getStatus() != expectedStatus) {
            throw new BusinessRuleException("INVALID_STATE_TRANSITION",
                    "Expected status " + expectedStatus + " but was " + trip.getStatus());
        }
        return trip;
    }

    private void transition(Trip trip, TripStatus next, UUID actorId, String actorType) {
        TripStatus current = trip.getStatus();
        TripStateMachine.validateTransition(current, next);
        trip.setStatus(next);
        tripRepository.save(trip);
        recordEvent(trip, current.name(), next.name(), actorId, actorType);
        tripTrackingBroadcaster.broadcastTripStatus(
                trip.getTripId(),
                next.name(),
                next == TripStatus.DRIVER_ARRIVED ? trip.getOtp() : null
        );
    }

    private void recordEvent(Trip trip, String previousStatus, String newStatus, UUID actorId, String actorType) {
        TripEvent event = new TripEvent();
        event.setEventId(Uuids.v7());
        event.setTripId(trip.getTripId());
        event.setEventType(newStatus);
        event.setPreviousStatus(previousStatus);
        event.setNewStatus(newStatus);
        event.setActorId(actorId);
        event.setActorType(actorType);
        tripEventRepository.save(event);
    }

    private String generateOtp() {
        return String.format("%04d", secureRandom.nextInt(10000));
    }

    private String buildNavigationUrl(BigDecimal lat, BigDecimal lng) {
        return "https://www.google.com/maps/dir/?api=1&destination=" + lat + "," + lng;
    }

    private Map<String, Object> toTripResponse(Trip trip) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("trip_id", trip.getTripId());
        response.put("status", trip.getStatus().name());
        response.put("estimated_fare", Map.of("min", trip.getEstimatedFareMin(), "max", trip.getEstimatedFareMax()));
        response.put("surge_multiplier", trip.getSurgeMultiplier());
        response.put("pickup_address", trip.getPickupAddress());
        response.put("pickup_lat", trip.getPickupLat());
        response.put("pickup_lng", trip.getPickupLng());
        response.put("destination_address", trip.getDestinationAddress());
        response.put("destination_lat", trip.getDestinationLat());
        response.put("destination_lng", trip.getDestinationLng());
        response.put("_links", Map.of(
                "self", "/v1/trips/" + trip.getTripId(),
                "cancel", "/v1/trips/" + trip.getTripId() + "/cancel",
                "stream", "ws://localhost:8080/v1/trips/" + trip.getTripId() + "/stream"
        ));
        return response;
    }

    private Map<String, Object> toDetailedTripResponse(Trip trip) {
        Map<String, Object> response = new LinkedHashMap<>(toTripResponse(trip));
        response.put("otp", trip.getStatus() == TripStatus.DRIVER_ARRIVED ? trip.getOtp() : null);
        response.put("eta_minutes", trip.getEstimatedDurationMin());
        response.put("started_at", trip.getTripStartedAt());
        response.put("ended_at", trip.getTripEndedAt());

        if (trip.getDriverId() != null) {
            Driver driver = identityService.getDriver(trip.getDriverId());
            Vehicle vehicle = identityService.getVehicle(trip.getVehicleId());
            response.put("driver", Map.of(
                    "driver_id", driver.getDriverId(),
                    "name", driver.getFullName(),
                    "rating", driver.getRating(),
                    "vehicle", Map.of(
                            "make", vehicle.getMake(),
                            "model", vehicle.getModel(),
                            "color", vehicle.getColor(),
                            "plate", vehicle.getRegistrationNumber()
                    )
            ));
        }

        var driverPosition = trip.getDriverId() == null ? null : locationService.getDriverPosition(trip.getDriverId());
        if (driverPosition != null) {
            Map<String, Object> driverLocation = new LinkedHashMap<>();
            driverLocation.put("lat", driverPosition.position().getLat());
            driverLocation.put("lng", driverPosition.position().getLng());
            driverLocation.put("heading", driverPosition.heading());
            driverLocation.put("updated_at", driverPosition.updatedAt().toString());
            response.put("driver_location", driverLocation);
        }

        response.put("rated_by_rider", ratingRepository.findByTripIdAndRatedBy(trip.getTripId(), "RIDER").isPresent());

        return response;
    }

    private Map<String, Object> toTripSummary(Trip trip) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("trip_id", trip.getTripId());
        summary.put("status", trip.getStatus().name());
        summary.put("pickup_address", trip.getPickupAddress());
        summary.put("destination_address", trip.getDestinationAddress());
        summary.put("requested_at", trip.getRequestedAt().toString());
        summary.put("final_fare", trip.getFinalFare());
        summary.put("rated_by_rider", ratingRepository.findByTripIdAndRatedBy(trip.getTripId(), "RIDER").isPresent());
        return summary;
    }

    private Map<String, Object> toDriverAcceptResponse(Trip trip) {
        Rider rider = identityService.getRider(trip.getRiderId());
        return Map.of(
                "trip_id", trip.getTripId(),
                "status", trip.getStatus().name(),
                "rider", Map.of(
                        "name", rider.getFullName(),
                        "rating", rider.getRating(),
                        "otp", trip.getOtp()
                ),
                "pickup", Map.of(
                        "lat", trip.getPickupLat(),
                        "lng", trip.getPickupLng(),
                        "address", trip.getPickupAddress()
                ),
                "destination_address", trip.getDestinationAddress(),
                "estimated_fare_share", (int) (trip.getEstimatedFareMin() * 0.8),
                "navigation_url", buildNavigationUrl(trip.getPickupLat(), trip.getPickupLng())
        );
    }
}

