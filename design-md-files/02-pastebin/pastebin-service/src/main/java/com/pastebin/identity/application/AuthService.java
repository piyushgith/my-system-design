package com.pastebin.identity.application;

import com.pastebin.identity.domain.IdentityDomainException;
import com.pastebin.identity.infrastructure.persistence.UserEntity;
import com.pastebin.identity.infrastructure.persistence.UserJpaRepository;
import com.pastebin.shared.UserId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class AuthService {

    private final UserJpaRepository userRepository;
    private final PasswordService passwordService;
    private final JwtTokenService jwtTokenService;

    public AuthService(UserJpaRepository userRepository,
                       PasswordService passwordService,
                       JwtTokenService jwtTokenService) {
        this.userRepository = userRepository;
        this.passwordService = passwordService;
        this.jwtTokenService = jwtTokenService;
    }

    @Transactional
    public AuthResult register(RegisterCommand command) {
        if (userRepository.findByEmailIgnoreCase(command.email()).isPresent()) {
            throw new IdentityDomainException("Email already registered");
        }
        Instant now = Instant.now();
        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail(command.email().trim().toLowerCase());
        user.setPasswordHash(passwordService.hash(command.password()));
        user.setDisplayName(command.displayName() != null ? command.displayName() : command.email());
        user.setActive(true);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        userRepository.save(user);
        UserId userId = new UserId(user.getId());
        String token = jwtTokenService.generateToken(userId, user.getEmail());
        return new AuthResult(userId.toString(), user.getEmail(), user.getDisplayName(), token);
    }

    @Transactional(readOnly = true)
    public AuthResult login(LoginCommand command) {
        UserEntity user = userRepository.findByEmailIgnoreCase(command.email().trim())
                .orElseThrow(() -> new IdentityDomainException("Invalid email or password"));
        if (!user.isActive() || !passwordService.matches(command.password(), user.getPasswordHash())) {
            throw new IdentityDomainException("Invalid email or password");
        }
        UserId userId = new UserId(user.getId());
        String token = jwtTokenService.generateToken(userId, user.getEmail());
        return new AuthResult(userId.toString(), user.getEmail(), user.getDisplayName(), token);
    }
}
