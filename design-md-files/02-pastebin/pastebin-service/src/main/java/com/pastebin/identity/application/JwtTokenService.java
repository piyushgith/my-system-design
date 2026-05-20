package com.pastebin.identity.application;

import com.pastebin.paste.application.PastebinProperties;
import com.pastebin.shared.UserId;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

@Component
public class JwtTokenService {

    private final SecretKey secretKey;
    private final long expirationMs;

    public JwtTokenService(PastebinProperties properties) {
        this.secretKey = Keys.hmacShaKeyFor(properties.jwt().secret().getBytes(StandardCharsets.UTF_8));
        this.expirationMs = properties.jwt().expirationMs();
    }

    public String generateToken(UserId userId, String email) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("email", email)
                .claim("roles", new String[]{"user"})
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(expirationMs)))
                .signWith(secretKey)
                .compact();
    }

    public Optional<UserId> parseUserId(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return Optional.of(UserId.of(claims.getSubject()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
