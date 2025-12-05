/*
package com.java.leave.management.system.config;

import com.java.leave.management.system.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

//@Configuration
//@EnableWebFluxSecurity
//@RequiredArgsConstructor
public class SecurityConfig {

    //private final JwtAuthenticationFilter jwtAuthenticationFilter;

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        http
            .csrf(csrf -> csrf.disable())
            .authorizeExchange(exchanges -> exchanges
                .pathMatchers("/api/v1/leave-management/auth/**").permitAll()
                .pathMatchers("/h2-console/**").permitAll()
                .anyExchange().authenticated()
            );
        
        // Add the JWT filter - it will be automatically applied as a WebFilter
        return http.build();
    }
}*/
