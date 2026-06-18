package com.test.ride.sharing.service.matching;

import com.test.ride.sharing.service.event.TripEventBus;
import com.test.ride.sharing.service.location.LocationService;
import com.test.ride.sharing.service.location.NearbyDriver;
import com.test.ride.sharing.service.notification.NotificationStrategyResolver;
import com.test.ride.sharing.service.shared.GeoPoint;
import com.test.ride.sharing.service.shared.Uuids;
import com.test.ride.sharing.service.shared.VehicleType;
import com.test.ride.sharing.service.tracking.TripTrackingBroadcaster;
import com.test.ride.sharing.service.trip.Trip;
import com.test.ride.sharing.service.trip.TripRepository;
import com.test.ride.sharing.service.trip.TripStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Service
public class MatchingOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(MatchingOrchestrator.class);

    private final TripRepository tripRepository;
    private final TripOfferRepository tripOfferRepository;
    private final LocationService locationService;
    private final MatchingStrategyResolver matchingStrategyResolver;
    private final MatchingProperties matchingProperties;
    private final NotificationStrategyResolver notificationStrategyResolver;
    private final TripEventBus tripEventBus;
    private final TripTrackingBroadcaster tripTrackingBroadcaster;
    private final TransactionTemplate transactionTemplate;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
    private final ConcurrentMap<UUID, ScheduledFuture<?>> expiryTasks = new ConcurrentHashMap<>();

    public MatchingOrchestrator(TripRepository tripRepository,
                                TripOfferRepository tripOfferRepository,
                                LocationService locationService,
                                MatchingStrategyResolver matchingStrategyResolver,
                                MatchingProperties matchingProperties,
                                NotificationStrategyResolver notificationStrategyResolver,
                                TripEventBus tripEventBus,
                                TripTrackingBroadcaster tripTrackingBroadcaster,
                                TransactionTemplate transactionTemplate) {
        this.tripRepository = tripRepository;
        this.tripOfferRepository = tripOfferRepository;
        this.locationService = locationService;
        this.matchingStrategyResolver = matchingStrategyResolver;
        this.matchingProperties = matchingProperties;
        this.notificationStrategyResolver = notificationStrategyResolver;
        this.tripEventBus = tripEventBus;
        this.tripTrackingBroadcaster = tripTrackingBroadcaster;
        this.transactionTemplate = transactionTemplate;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("matchingExecutor")
    public void onTripMatchingRequested(TripMatchingRequestedEvent event) {
        startMatching(event.tripId());
    }

    public void startMatching(UUID tripId) {
        transactionTemplate.executeWithoutResult(status -> {
            Trip trip = tripRepository.findById(tripId).orElse(null);
            if (trip == null || trip.getStatus() != TripStatus.MATCHING) {
                return;
            }
            tripEventBus.publish("TRIP_MATCHING_STARTED", tripId, trip.getRiderId(), null,
                    "{\"round\":0}");
        });
        offerNextDriver(tripId, 0);
    }

    public void continueMatching(UUID tripId) {
        transactionTemplate.executeWithoutResult(status -> {
            Trip trip = tripRepository.findById(tripId).orElse(null);
            if (trip == null || trip.getStatus() != TripStatus.MATCHING) {
                return;
            }
            int lastRound = tripOfferRepository.findByTripIdOrderByOfferedAtDesc(tripId).stream()
                    .mapToInt(TripOffer::getMatchingRound)
                    .max()
                    .orElse(0);
            offerNextDriver(tripId, lastRound);
        });
    }

    public void cancelOfferExpiry(UUID offerId) {
        ScheduledFuture<?> task = expiryTasks.remove(offerId);
        if (task != null) {
            task.cancel(false);
        }
    }

    public Optional<TripOffer> getPendingOfferForDriver(UUID driverId) {
        return tripOfferRepository.findFirstByDriverIdAndStatusOrderByOfferedAtDesc(driverId, OfferStatus.PENDING)
                .filter(offer -> offer.getExpiresAt().isAfter(Instant.now()));
    }

    public void validateAcceptOffer(UUID tripId, UUID driverId) {
        TripOffer offer = tripOfferRepository.findFirstByTripIdAndStatusOrderByOfferedAtDesc(tripId, OfferStatus.PENDING)
                .orElseThrow(() -> new com.test.ride.sharing.service.web.error.BusinessRuleException(
                        "INVALID_STATE_TRANSITION", "No pending offer for this trip"));
        if (!offer.getDriverId().equals(driverId)) {
            throw new com.test.ride.sharing.service.web.error.BusinessRuleException(
                    "FORBIDDEN", "This trip offer is assigned to another driver");
        }
        if (offer.getExpiresAt().isBefore(Instant.now())) {
            throw new com.test.ride.sharing.service.web.error.BusinessRuleException(
                    "INVALID_STATE_TRANSITION", "Trip offer has expired");
        }
    }

    public void markOfferAccepted(UUID tripId, UUID driverId) {
        transactionTemplate.executeWithoutResult(status -> {
            tripOfferRepository.findFirstByTripIdAndStatusOrderByOfferedAtDesc(tripId, OfferStatus.PENDING)
                    .ifPresent(offer -> {
                        cancelOfferExpiry(offer.getOfferId());
                        offer.setStatus(OfferStatus.ACCEPTED);
                        tripOfferRepository.save(offer);
                    });
        });
    }

    public void markOfferRejected(UUID tripId, UUID driverId) {
        transactionTemplate.executeWithoutResult(status -> {
            tripOfferRepository.findFirstByTripIdAndStatusOrderByOfferedAtDesc(tripId, OfferStatus.PENDING)
                    .filter(offer -> offer.getDriverId().equals(driverId))
                    .ifPresent(offer -> {
                        cancelOfferExpiry(offer.getOfferId());
                        offer.setStatus(OfferStatus.REJECTED);
                        tripOfferRepository.save(offer);
                    });
        });
        continueMatching(tripId);
    }

    private void offerNextDriver(UUID tripId, int round) {
        transactionTemplate.executeWithoutResult(status -> {
            Trip trip = tripRepository.findById(tripId).orElse(null);
            if (trip == null || trip.getStatus() != TripStatus.MATCHING) {
                return;
            }

            if (Duration.between(trip.getRequestedAt(), Instant.now()).getSeconds()
                    >= matchingProperties.getTotalTimeoutSeconds()) {
                cancelTripNoDrivers(trip);
                return;
            }

            if (round >= matchingProperties.getMaxRounds()) {
                cancelTripNoDrivers(trip);
                return;
            }

            double radiusKm = matchingProperties.getSearchRadiusKm() + (round * matchingProperties.getRadiusExpansionKm());
            GeoPoint pickup = new GeoPoint(trip.getPickupLat(), trip.getPickupLng());
            Set<UUID> declined = new HashSet<>(tripOfferRepository.findDeclinedDriverIds(tripId));

            List<NearbyDriver> candidates = locationService.findNearbyDrivers(
                    trip.getCityId(), pickup, radiusKm, trip.getVehicleTypeRequested(), 20);

            Optional<UUID> selected = matchingStrategyResolver.active()
                    .selectDriver(candidates.stream()
                            .filter(c -> !declined.contains(c.driverId()))
                            .filter(c -> tripRepository.findActiveByDriverId(c.driverId()).isEmpty())
                            .toList(), pickup)
                    .map(NearbyDriver::driverId);

            if (selected.isEmpty()) {
                if (round + 1 < matchingProperties.getMaxRounds()) {
                    offerNextDriver(tripId, round + 1);
                } else {
                    cancelTripNoDrivers(trip);
                }
                return;
            }

            UUID driverId = selected.get();
            TripOffer offer = new TripOffer();
            offer.setOfferId(Uuids.v7());
            offer.setTripId(tripId);
            offer.setDriverId(driverId);
            offer.setMatchingRound(round);
            offer.setExpiresAt(Instant.now().plusSeconds(matchingProperties.getOfferTimeoutSeconds()));
            tripOfferRepository.save(offer);

            notificationStrategyResolver.notifyUser(
                    driverId,
                    "New ride request",
                    "Tap to accept — expires in " + matchingProperties.getOfferTimeoutSeconds() + "s",
                    "/v1/driver/trips/" + tripId + "/accept"
            );

            tripEventBus.publish("DRIVER_OFFERED", tripId, trip.getRiderId(), driverId,
                    "{\"offerId\":\"" + offer.getOfferId() + "\",\"round\":" + round + "}");

            scheduleExpiry(offer.getOfferId(), tripId, driverId);
        });
    }

    private void scheduleExpiry(UUID offerId, UUID tripId, UUID driverId) {
        ScheduledFuture<?> future = scheduler.schedule(() -> onOfferExpired(offerId, tripId, driverId),
                matchingProperties.getOfferTimeoutSeconds(), TimeUnit.SECONDS);
        expiryTasks.put(offerId, future);
    }

    private void onOfferExpired(UUID offerId, UUID tripId, UUID driverId) {
        transactionTemplate.executeWithoutResult(status -> {
            TripOffer offer = tripOfferRepository.findById(offerId).orElse(null);
            if (offer == null || offer.getStatus() != OfferStatus.PENDING) {
                return;
            }
            offer.setStatus(OfferStatus.EXPIRED);
            tripOfferRepository.save(offer);
            log.info("Offer {} expired for trip {} driver {}", offerId, tripId, driverId);
        });
        continueMatching(tripId);
    }

    private void cancelTripNoDrivers(Trip trip) {
        trip.setStatus(TripStatus.CANCELLED);
        trip.setCancelledBy("SYSTEM");
        trip.setCancellationReason("NO_DRIVERS_AVAILABLE");
        trip.setCancelledAt(Instant.now());
        tripRepository.save(trip);

        tripEventBus.publish("TRIP_CANCELLED_NO_DRIVERS", trip.getTripId(), trip.getRiderId(), null, "{}");
        notificationStrategyResolver.notifyUser(
                trip.getRiderId(),
                "No drivers available",
                "We couldn't find a driver nearby. Please try again.",
                "/v1/trips/" + trip.getTripId()
        );
        tripTrackingBroadcaster.broadcastTripStatus(trip.getTripId(), TripStatus.CANCELLED.name(), null);
    }
}
