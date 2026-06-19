package com.ecommerce.common.ratelimit;

import com.ecommerce.common.exception.GlobalExceptionHandler.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

/**
 * Lightweight fixed-window rate limiter backed by Redis counters. Protects the
 * unauthenticated auth endpoints (brute force) and checkout (abuse) without pulling
 * in a heavier dependency. Keyed by client IP + endpoint bucket.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final int authLimit;
    private final int checkoutLimit;
    private final Duration window;
    private final boolean trustForwardedHeaders;

    public RateLimitFilter(StringRedisTemplate redis,
                           ObjectMapper objectMapper,
                           @Value("${app.ratelimit.enabled:true}") boolean enabled,
                           @Value("${app.ratelimit.auth-limit:10}") int authLimit,
                           @Value("${app.ratelimit.checkout-limit:20}") int checkoutLimit,
                           @Value("${app.ratelimit.window-seconds:60}") long windowSeconds,
                           @Value("${app.ratelimit.trust-forwarded-headers:false}") boolean trustForwardedHeaders) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.authLimit = authLimit;
        this.checkoutLimit = checkoutLimit;
        this.window = Duration.ofSeconds(windowSeconds);
        this.trustForwardedHeaders = trustForwardedHeaders;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        int limit = limitFor(request);
        if (!enabled || limit < 0) {
            filterChain.doFilter(request, response);
            return;
        }

        String key = "rl:" + bucket(request) + ":" + clientIp(request);
        long count = increment(key);
        if (count > limit) {
            reject(response);
            return;
        }
        filterChain.doFilter(request, response);
    }

    /** Returns the request limit for the matched bucket, or -1 if the path is not rate limited. */
    private int limitFor(HttpServletRequest request) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return -1;
        }
        String path = request.getRequestURI();
        if (path.startsWith("/v1/auth/")) {
            return authLimit;
        }
        if ("POST".equalsIgnoreCase(request.getMethod()) && path.equals("/v1/orders/checkout")) {
            return checkoutLimit;
        }
        return -1;
    }

    private String bucket(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/v1/auth/") ? "auth" : "checkout";
    }

    private long increment(String key) {
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, window);
        }
        return count == null ? 1L : count;
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = trustForwardedHeaders ? request.getHeader("X-Forwarded-For") : null;
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private void reject(HttpServletResponse response) throws IOException {
        log.warn("Rate limit exceeded");
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(),
                new ErrorResponse("RATE_LIMITED", "Too many requests, please slow down", List.of()));
    }
}
