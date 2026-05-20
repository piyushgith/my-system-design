package com.java.leave.management.system.controller;

import com.java.leave.management.system.dto.*;
import com.java.leave.management.system.security.JwtTokenProvider;
import com.java.leave.management.system.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;


@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("${app.leave-management.base-path}/auth")
public class AuthController {

    private final JwtTokenProvider tokenProvider;

    private final ReactiveAuthenticationManager authenticationManager;

    private final UserService userService;


    @GetMapping("/health")
    public Mono<ResponseEntity<String>> healthCheck() {
        return Mono.just(ResponseEntity.ok("Employee Leave Management Service is up and running"));
    }

    @PostMapping("/register")
    public Mono<ResponseEntity<ApiResponse<UsersDto>>> register(@RequestBody Mono<UsersDto> userDtoMono) {
        return userDtoMono
                .flatMap(userService::createUser)
                .map(createdUser -> ResponseEntity.ok(
                        ApiResponse.success("Registration successful", createdUser)
                ))
                .onErrorResume(error -> Mono.just(ResponseEntity
                        .status(HttpStatus.BAD_REQUEST)
                        .body(ApiResponse.error("Registration failed: " + error.getMessage()))
                ));
    }

    @PostMapping("/login1")
    public Mono<ResponseEntity> login1(@Valid @RequestBody Mono<LoginRequest> authRequest) {
        return authRequest
                .flatMap(login -> this.authenticationManager
                        .authenticate(new UsernamePasswordAuthenticationToken(login.getUserName(), login.getPassword()))
                        .map(this.tokenProvider::createToken))
                .map(jwt -> {
                    HttpHeaders httpHeaders = new HttpHeaders();
                    httpHeaders.add(HttpHeaders.AUTHORIZATION, "Bearer " + jwt);
                    var tokenBody = Map.of("access_token", jwt);
                    return new ResponseEntity<>(tokenBody, httpHeaders, HttpStatus.OK);
                });
    }

    @PostMapping("/login")
    public Mono<ResponseEntity<TokenResponse>> login(@Valid @RequestBody Mono<LoginRequest> authRequest) {
        return authRequest
                .flatMap(login -> this.authenticationManager
                        .authenticate(new UsernamePasswordAuthenticationToken(login.getUserName(), login.getPassword()))
                        .map(authentication -> {
                            String accessToken = this.tokenProvider.createToken(authentication);
                            String refreshToken = this.tokenProvider.createRefreshToken(authentication.getName());
                            return TokenResponse.builder()
                                    .accessToken(accessToken)
                                    .refreshToken(refreshToken)
                                    .tokenType("Bearer")
                                    .expiresIn(this.tokenProvider.getAccessTokenExpiryTime() / 1000)
                                    .build();
                        }))
                .map(tokenResponse -> ResponseEntity.ok()
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenResponse.getAccessToken())
                        .body(tokenResponse));
    }

    @PostMapping("/refresh")
    public Mono<ResponseEntity<?>> refreshToken(@Valid @RequestBody Mono<RefreshTokenRequest> refreshRequest) {
        return refreshRequest
                .flatMap(request -> {
                    String refreshToken = request.getRefreshToken();

                    // Validate the refresh token
                    if (!this.tokenProvider.validateRefreshToken(refreshToken)) {
                        return Mono.just(ResponseEntity
                                .status(HttpStatus.UNAUTHORIZED)
                                .body(ApiResponse.error("Invalid or expired refresh token")));
                    }

                    // Extract username from refresh token
                    String username = this.tokenProvider.getUsernameFromRefreshToken(refreshToken);
                    if (username == null) {
                        return Mono.just(ResponseEntity
                                .status(HttpStatus.UNAUTHORIZED)
                                .body(ApiResponse.error("Unable to extract username from refresh token")));
                    }

                    // Generate new access token
                    String newAccessToken = this.tokenProvider.createToken(
                            this.tokenProvider.getAuthentication(refreshToken)
                    );
                    TokenResponse tokenResponse = TokenResponse.builder()
                            .accessToken(newAccessToken)
                            .refreshToken(refreshToken)
                            .expiresIn(this.tokenProvider.getAccessTokenExpiryTime() / 1000)
                            .build();
                    return Mono.just(ResponseEntity.ok(tokenResponse));
                });
    }
}
