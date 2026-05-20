package com.test.banking.core.auth.config;

import com.test.banking.core.kyc.api.KycPublicApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private static final String TELLER_USERNAME = "teller";
    private static final String DEV_CUSTOMER_PASSWORD = "customer";

    @Bean
    public UserDetailsService userDetailsService(PasswordEncoder passwordEncoder, KycPublicApi kycPublicApi) {
        UserDetails teller = User.builder()
                .username(TELLER_USERNAME)
                .password(passwordEncoder.encode("teller"))
                .roles("TELLER")
                .build();
        String customerPasswordHash = passwordEncoder.encode(DEV_CUSTOMER_PASSWORD);

        return username -> {
            if (TELLER_USERNAME.equals(username)) {
                return teller;
            }
            if (kycPublicApi.customerExists(username)) {
                return User.builder()
                        .username(username)
                        .password(customerPasswordHash)
                        .roles("CUSTOMER")
                        .build();
            }
            throw new UsernameNotFoundException("User not found: " + username);
        };
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/info",
                                "/api-docs/**", "/swagger-ui/**", "/swagger-ui.html",
                                "/api/v1/reference/**").permitAll()
                        .anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(new HttpStatusEntryPoint(UNAUTHORIZED))
                        .accessDeniedHandler(accessDeniedHandler()));
        return http.build();
    }

    @Bean
    public AccessDeniedHandler accessDeniedHandler() {
        return (request, response, accessDeniedException) -> {
            response.setStatus(FORBIDDEN.value());
            response.setContentType("application/json");
            response.getWriter().write("""
                    {"error":{"code":"FORBIDDEN","message":"Access denied"}}
                    """);
        };
    }
}
