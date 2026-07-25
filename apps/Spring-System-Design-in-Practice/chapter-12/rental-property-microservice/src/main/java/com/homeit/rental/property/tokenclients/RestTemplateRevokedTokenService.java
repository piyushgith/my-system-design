package com.homeit.rental.property.tokenclients;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import java.util.logging.Logger;

import java.util.Objects;

@Service
@Slf4j
public class RestTemplateRevokedTokenService {

    private final RestTemplate restTemplate;

    public RestTemplateRevokedTokenService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    // this code is for sample purposes, you should choose carefully which ones you want to use in conjunction
    @CircuitBreaker(name = "revokeTokenCircuitBreaker", fallbackMethod = "revokeTokenServiceOutage")
    @Retry(name = "revokeTokenServiceRetry")
    @RateLimiter(name = "revokeTokenServiceRateLimiter", fallbackMethod = "rateLimitFallback")
    @Bulkhead(name = "revokeTokenBulkhead", fallbackMethod = "bulkheadFallback")
    public boolean isTokenRevoked(String token) {
        String url = "http://revoke-token-service/api/revoke-tokens?token=" + token;

        // Create headers and add them to the request
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Basic bXlBcHBJZDpteVNlY3JldA=="); // Example header

        HttpEntity<String> entity = new HttpEntity<>(headers);

        // Use exchange to send the request with headers
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

        if(response.getStatusCode().value() == 204) {
            return false;
        }

        if(response.getStatusCode().value() == 200) {
            return Objects.requireNonNull(response.getBody()).contains(token);
        }

        return true;
    }

    private boolean revokeTokenServiceOutage(Exception ex) {
        log.error("Revoke Token service is out! " +
            "All tokens are considered not revoked until " +
            "revoke service is back", ex);
        return false; // no tokens are considered revoked while revoke service is down
    }

    private boolean rateLimitFallback(Exception ex) {
        log.error("Rate limit triggered! Too many requests", ex);
        return false; // no tokens are considered revoked while revoke service is down
    }

    private boolean bulkheadFallback(Exception ex) {
        log.error("Bulk head triggered! Too many concurrent requests!", ex);
        return false; // no tokens are considered revoked while revoke service is down
    }

    private boolean timeoutFallback(Exception ex) {
        log.error("Revoke Token service took to long to respond!", ex);
        return false; // no tokens are considered revoked while revoke service is down
    }
}