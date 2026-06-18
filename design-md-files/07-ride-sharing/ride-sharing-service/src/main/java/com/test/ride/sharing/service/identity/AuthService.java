package com.test.ride.sharing.service.identity;

import com.test.ride.sharing.service.shared.UserRole;
import com.test.ride.sharing.service.web.AuthProperties;
import com.test.ride.sharing.service.shared.Uuids;
import com.test.ride.sharing.service.web.error.BusinessRuleException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AuthService {

    private final AuthProperties authProperties;
    private final IdentityService identityService;
    private final Map<String, OtpSession> otpSessions = new ConcurrentHashMap<>();

    public AuthService(AuthProperties authProperties, IdentityService identityService) {
        this.authProperties = authProperties;
        this.identityService = identityService;
    }

    public Map<String, Object> requestOtp(String phoneNumber) {
        String otpRequestId = Uuids.v7().toString();
        otpSessions.put(otpRequestId, new OtpSession(phoneNumber, Instant.now().plusSeconds(120)));
        return Map.of(
                "otp_request_id", otpRequestId,
                "expires_in_seconds", 120,
                "dev_hint", "Use OTP " + authProperties.getMockOtp() + " in dev mode"
        );
    }

    @Transactional
    public Map<String, Object> verifyOtp(String otpRequestId, String otpCode, UserRole preferredRole) {
        OtpSession session = otpSessions.remove(otpRequestId);
        if (session == null || session.expiresAt().isBefore(Instant.now())) {
            throw new BusinessRuleException("INVALID_OTP", "OTP request expired or not found");
        }
        if (!authProperties.getMockOtp().equals(otpCode)) {
            throw new BusinessRuleException("INVALID_OTP", "Invalid OTP code");
        }

        UserRole role = preferredRole == null ? UserRole.RIDER : preferredRole;
        UUID userId = switch (role) {
            case RIDER -> identityService.registerRider(session.phoneNumber(), "Rider " + session.phoneNumber(), null)
                    .getRiderId();
            case DRIVER -> identityService.registerDriver(session.phoneNumber(), "Driver " + session.phoneNumber(),
                    identityService.getCityByCodeOrThrow("BLR").getCityId()).getDriverId();
            case ADMIN -> Uuids.v7();
        };

        return Map.of(
                "access_token", "dev-token-" + userId,
                "refresh_token", "dev-refresh-" + userId,
                "user_type", role.name(),
                "user_id", userId.toString()
        );
    }

    private record OtpSession(String phoneNumber, Instant expiresAt) {
    }
}
