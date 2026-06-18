package com.test.ride.sharing.service.identity.web;

import com.test.ride.sharing.service.identity.AuthService;
import com.test.ride.sharing.service.shared.UserRole;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/v1/auth")
@SecurityRequirements
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/otp/request")
    public Map<String, Object> requestOtp(@Valid @RequestBody OtpRequest request) {
        return authService.requestOtp(request.phoneNumber());
    }

    @PostMapping("/otp/verify")
    public Map<String, Object> verifyOtp(@Valid @RequestBody OtpVerifyRequest request) {
        return authService.verifyOtp(request.otpRequestId(), request.otpCode(), request.userType());
    }

    public record OtpRequest(@NotBlank String phoneNumber) {
    }

    public record OtpVerifyRequest(
            @NotBlank String otpRequestId,
            @NotBlank String otpCode,
            UserRole userType
    ) {
    }
}
