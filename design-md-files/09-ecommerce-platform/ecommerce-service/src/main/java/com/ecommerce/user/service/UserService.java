package com.ecommerce.user.service;

import com.ecommerce.common.exception.ConflictException;
import com.ecommerce.common.exception.NotFoundException;
import com.ecommerce.common.security.JwtService;
import com.ecommerce.user.domain.User;
import com.ecommerce.user.domain.UserRole;
import com.ecommerce.user.domain.UserStatus;
import com.ecommerce.user.repository.UserRepository;
import com.ecommerce.user.service.dto.AuthResponse;
import com.ecommerce.user.service.dto.LoginRequest;
import com.ecommerce.user.service.dto.RegisterRequest;
import com.ecommerce.user.service.dto.UserProfileResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final TokenService tokenService;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new ConflictException("Email already registered");
        }
        User user = User.builder()
                .name(request.name())
                .email(request.email())
                .passwordHash(passwordEncoder.encode(request.password()))
                .role(UserRole.CUSTOMER)
                .status(UserStatus.ACTIVE)
                .build();
        user = userRepository.save(user);
        return toAuthResponse(user);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new IllegalArgumentException("Invalid email or password"));
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new IllegalArgumentException("Invalid email or password");
        }
        if (!user.isActive()) {
            throw new IllegalStateException("Account is suspended");
        }
        return toAuthResponse(user);
    }

    @Transactional(readOnly = true)
    public UserProfileResponse getProfile(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        return UserProfileResponse.from(user);
    }

    /** Exchanges a valid refresh token for a fresh access token, rotating the refresh token. */
    @Transactional(readOnly = true)
    public AuthResponse refresh(String refreshToken) {
        UUID userId = tokenService.resolve(refreshToken)
                .orElseThrow(() -> new IllegalArgumentException("Invalid or expired refresh token"));
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Invalid or expired refresh token"));
        if (!user.isActive()) {
            throw new IllegalStateException("Account is suspended");
        }
        // Rotate: revoke the used refresh token and issue a new one.
        tokenService.revoke(refreshToken);
        return toAuthResponse(user);
    }

    /** Revokes a refresh token (logout). Access tokens expire on their own short TTL. */
    public void logout(String refreshToken) {
        tokenService.revoke(refreshToken);
    }

    private AuthResponse toAuthResponse(User user) {
        String token = jwtService.generateToken(user.getId().toString(), user.getRole().name());
        String refreshToken = tokenService.issue(user.getId());
        return new AuthResponse(token, refreshToken, user.getId(), user.getName(), user.getRole().name());
    }
}
