package com.ecommerce.common.security;

import com.ecommerce.user.domain.User;
import com.ecommerce.user.repository.UserRepository;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserRepository userRepository;

    @Override
    protected void doFilterInternal(@SuppressWarnings("NullableProblems") HttpServletRequest request,
                                    @SuppressWarnings("NullableProblems") HttpServletResponse response,
                                    @SuppressWarnings("NullableProblems") FilterChain filterChain)
            throws ServletException, IOException {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(7);
        try {
            AuthenticatedUser tokenUser = jwtService.extractUser(token);
            // Re-validate against the DB so suspended accounts and role changes take effect
            // immediately, instead of trusting potentially stale token claims.
            User dbUser = userRepository.findById(tokenUser.userId()).orElse(null);
            if (dbUser != null && dbUser.isActive()) {
                AuthenticatedUser principal = new AuthenticatedUser(dbUser.getId(), dbUser.getRole().name());
                var auth = new UsernamePasswordAuthenticationToken(
                        principal, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + dbUser.getRole().name()))
                );
                auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
            // unknown or suspended user — proceed unauthenticated; security config rejects protected routes
        } catch (JwtException ignored) {
            // invalid token — proceed unauthenticated; security config rejects if endpoint requires auth
        }

        filterChain.doFilter(request, response);
    }
}
