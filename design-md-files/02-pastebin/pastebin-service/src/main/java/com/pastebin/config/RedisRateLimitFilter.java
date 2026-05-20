package com.pastebin.config;

import com.pastebin.paste.application.PastebinProperties;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

@Component
public class RedisRateLimitFilter extends OncePerRequestFilter {

    private final StringRedisTemplate redisTemplate;
    private final PastebinProperties properties;
    private final MeterRegistry meterRegistry;

    public RedisRateLimitFilter(StringRedisTemplate redisTemplate,
                                PastebinProperties properties,
                                MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if ("POST".equalsIgnoreCase(request.getMethod()) && request.getRequestURI().endsWith("/api/v1/pastes")) {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            boolean isAuthenticated = auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken);
            if (!isAuthenticated) {
                String clientIp = resolveClientIp(request);
                long windowStart = Instant.now().getEpochSecond() / 3600;
                String key = "rate:create:" + clientIp + ":" + windowStart;
                Long count = redisTemplate.opsForValue().increment(key);
                if (count != null && count == 1L) {
                    redisTemplate.expire(key, Duration.ofHours(1));
                }
                int limit = properties.rateLimit().anonymousCreatePerHour();
                long reset = (windowStart + 1) * 3600;
                long remaining = count != null ? Math.max(0L, limit - count) : limit;
                response.setHeader("X-RateLimit-Limit", String.valueOf(limit));
                response.setHeader("X-RateLimit-Remaining", String.valueOf(remaining));
                response.setHeader("X-RateLimit-Reset", String.valueOf(reset));
                if (count != null && count > limit) {
                    response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                    response.setHeader("Retry-After", "60");
                    response.setContentType("application/problem+json");
                    response.getWriter().write("""
                            {"type":"https://api.pastebin.io/problems/rate-limit","title":"Too Many Requests","status":429}
                            """);
                    return;
                }
            }
        }
        filterChain.doFilter(request, response);
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
