package com.test.url.shortner.shared;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(1)
public class TraceIdFilter extends OncePerRequestFilter {

	private static final String REQUEST_HEADER = "X-Request-Id";
	private static final String MDC_KEY = "traceId";

	@Override
	protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
			@NonNull FilterChain chain) throws ServletException, IOException {
		String traceId = Optional.ofNullable(request.getHeader(REQUEST_HEADER))
				.filter(s -> !s.isBlank())
				.orElse(UUID.randomUUID().toString());
		MDC.put(MDC_KEY, traceId);
		try {
			chain.doFilter(request, response);
		}
		finally {
			MDC.remove(MDC_KEY);
		}
	}
}
