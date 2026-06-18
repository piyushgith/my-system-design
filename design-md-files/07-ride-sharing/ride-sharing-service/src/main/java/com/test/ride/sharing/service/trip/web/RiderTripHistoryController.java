package com.test.ride.sharing.service.trip.web;

import com.test.ride.sharing.service.shared.UserRole;
import com.test.ride.sharing.service.trip.TripService;
import com.test.ride.sharing.service.web.AuthContext;
import com.test.ride.sharing.service.web.AuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/riders/me")
public class RiderTripHistoryController {

    private final TripService tripService;

    public RiderTripHistoryController(TripService tripService) {
        this.tripService = tripService;
    }

    @GetMapping("/trips")
    public Map<String, Object> listTrips(HttpServletRequest request) {
        AuthContext auth = AuthInterceptor.requireRole(request, UserRole.RIDER);
        return tripService.listRiderTrips(auth.userId(), 20);
    }

    @GetMapping("/trips/active")
    public Map<String, Object> activeTrip(HttpServletRequest request) {
        AuthContext auth = AuthInterceptor.requireRole(request, UserRole.RIDER);
        return tripService.getActiveTripForRider(auth.userId());
    }
}
