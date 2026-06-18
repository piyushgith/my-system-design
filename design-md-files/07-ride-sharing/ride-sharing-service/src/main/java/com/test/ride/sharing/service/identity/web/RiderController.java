package com.test.ride.sharing.service.identity.web;

import com.test.ride.sharing.service.identity.IdentityService;
import com.test.ride.sharing.service.identity.Rider;
import com.test.ride.sharing.service.identity.RiderRepository;
import com.test.ride.sharing.service.shared.UserRole;
import com.test.ride.sharing.service.web.AuthContext;
import com.test.ride.sharing.service.web.AuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1/riders")
public class RiderController {

    private final IdentityService identityService;
    private final RiderRepository riderRepository;

    public RiderController(IdentityService identityService, RiderRepository riderRepository) {
        this.identityService = identityService;
        this.riderRepository = riderRepository;
    }

    @PostMapping
    public Map<String, Object> register(@Valid @RequestBody RegisterRiderRequest request) {
        Rider rider = identityService.registerRider(request.phoneNumber(), request.fullName(), request.email());
        return Map.of("rider_id", rider.getRiderId(), "status", rider.getStatus().name());
    }

    @GetMapping("/me")
    public Map<String, Object> me(HttpServletRequest request) {
        AuthContext auth = AuthInterceptor.requireRole(request, UserRole.RIDER);
        Rider rider = identityService.getRider(auth.userId());
        return Map.of(
                "rider_id", rider.getRiderId(),
                "full_name", rider.getFullName(),
                "phone_number", rider.getPhoneNumber(),
                "email", rider.getEmail(),
                "rating", rider.getRating(),
                "total_trips", rider.getTotalTrips(),
                "status", rider.getStatus().name()
        );
    }

    @PatchMapping("/me")
    public Map<String, Object> updateProfile(@RequestBody UpdateRiderRequest body, HttpServletRequest request) {
        AuthContext auth = AuthInterceptor.requireRole(request, UserRole.RIDER);
        Rider rider = identityService.getRider(auth.userId());
        if (body.fullName() != null) {
            rider.setFullName(body.fullName());
        }
        if (body.email() != null) {
            rider.setEmail(body.email());
        }
        riderRepository.save(rider);
        return Map.of("rider_id", rider.getRiderId(), "full_name", rider.getFullName(), "email", rider.getEmail());
    }

    public record RegisterRiderRequest(@NotBlank String phoneNumber, @NotBlank String fullName, String email) {
    }

    public record UpdateRiderRequest(String fullName, String email) {
    }
}
