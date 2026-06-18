package com.test.ride.sharing.service.web;

import com.test.ride.sharing.service.shared.UserRole;
import com.test.ride.sharing.service.web.error.ForbiddenException;
import com.test.ride.sharing.service.web.error.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;
import java.util.Set;

@Component
public class AuthInterceptor implements HandlerInterceptor {

    public static final String AUTH_CONTEXT_ATTR = "authContext";

    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/v1/auth/otp/request",
            "/v1/auth/otp/verify"
    );

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String path = request.getRequestURI();
        if (PUBLIC_PATHS.contains(path) || path.startsWith("/h2-console")) {
            return true;
        }

        DevAuthSupport.ResolvedAuth auth = DevAuthSupport.resolve(request);
        request.setAttribute(AUTH_CONTEXT_ATTR, new AuthContext(auth.userId(), auth.role()));
        return true;
    }

    public static AuthContext requireAuth(HttpServletRequest request) {
        AuthContext context = (AuthContext) request.getAttribute(AUTH_CONTEXT_ATTR);
        if (context == null) {
            throw new UnauthorizedException("Authentication required");
        }
        return context;
    }

    public static AuthContext requireRole(HttpServletRequest request, UserRole... roles) {
        AuthContext context = requireAuth(request);
        if (Arrays.stream(roles).noneMatch(r -> r == context.role())) {
            throw new ForbiddenException("Insufficient permissions for this endpoint");
        }
        return context;
    }
}
