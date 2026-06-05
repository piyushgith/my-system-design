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

    private AuthResponse toAuthResponse(User user) {
        String token = jwtService.generateToken(user.getId().toString(), user.getRole().name());
        return new AuthResponse(token, user.getId(), user.getName(), user.getRole().name());
    }
}
