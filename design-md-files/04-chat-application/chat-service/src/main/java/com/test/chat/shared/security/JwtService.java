package com.test.chat.shared.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {

	private final SecretKey secretKey;
	private final long expirationHours;

	public JwtService(
			@Value("${chat.jwt.secret}") String secret,
			@Value("${chat.jwt.expiration-hours}") long expirationHours) {
		this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
		this.expirationHours = expirationHours;
	}

	public String generateToken(UUID userId) {
		Instant now = Instant.now();
		return Jwts.builder()
				.subject(userId.toString())
				.issuedAt(Date.from(now))
				.expiration(Date.from(now.plus(expirationHours, ChronoUnit.HOURS)))
				.signWith(secretKey)
				.compact();
	}

	public UUID parseUserId(String token) {
		Claims claims = Jwts.parser()
				.verifyWith(secretKey)
				.build()
				.parseSignedClaims(token)
				.getPayload();
		return UUID.fromString(claims.getSubject());
	}
}
