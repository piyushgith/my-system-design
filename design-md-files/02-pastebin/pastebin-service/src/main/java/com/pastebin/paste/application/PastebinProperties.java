package com.pastebin.paste.application;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "pastebin")
@Validated
public record PastebinProperties(
        String baseUrl,
        ContentProperties content,
        JwtProperties jwt,
        RateLimitProperties rateLimit
) {
    public record ContentProperties(int inlineThresholdBytes, long maxSizeBytes) {
    }

    public record JwtProperties(@NotBlank String secret, long expirationMs) {
    }

    public record RateLimitProperties(int anonymousCreatePerHour, int anonymousReadPerMinute, int authenticatedCreatePerDay) {
    }
}
