package com.ecommerce.common.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RateLimitFilterTest {

    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> values;
    @Mock FilterChain chain;

    @Test
    void skipsCorsPreflightRequests() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(redis, new ObjectMapper(),
                true, 10, 20, 60, false);
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/v1/auth/login");

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        verify(chain).doFilter(any(), any());
        verifyNoInteractions(redis);
    }

    @Test
    void ignoresForwardedForByDefault() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(redis, new ObjectMapper(),
                true, 10, 20, 60, false);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/auth/login");
        request.setRemoteAddr("10.0.0.5");
        request.addHeader("X-Forwarded-For", "203.0.113.10");
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment("rl:auth:10.0.0.5")).thenReturn(1L);

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        verify(values).increment("rl:auth:10.0.0.5");
        verify(redis).expire("rl:auth:10.0.0.5", Duration.ofSeconds(60));
    }
}
