package com.java.leave.management.system.controller;

import com.java.leave.management.system.dto.ApiResponse;
import com.java.leave.management.system.dto.EmployeeDto;
import com.java.leave.management.system.dto.LoginRequest;
import com.java.leave.management.system.dto.UsersDto;
import com.java.leave.management.system.security.JwtTokenProvider;
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

    @GetMapping("/health")
    public Mono<ResponseEntity<String>> healthCheck() {
        return Mono.just(ResponseEntity.ok("Employee Leave Management Service is up and running"));
    }

    @PostMapping("/login")
    public Mono<ResponseEntity> login(@Valid @RequestBody Mono<LoginRequest> authRequest) {
        return authRequest
                .flatMap(login -> this.authenticationManager
                        .authenticate(new UsernamePasswordAuthenticationToken(login.getEmailId(), login.getPassword()))
                        .map(this.tokenProvider::createToken))
                .map(jwt -> {
                    HttpHeaders httpHeaders = new HttpHeaders();
                    httpHeaders.add(HttpHeaders.AUTHORIZATION, "Bearer " + jwt);
                    var tokenBody = Map.of("access_token", jwt);
                    return new ResponseEntity<>(tokenBody, httpHeaders, HttpStatus.OK);
                });
    }

/*    @PostMapping("/login")
    public Mono<ResponseEntity<ApiResponse<String>>> login() {
        // Placeholder implementation
        return Mono.just(ResponseEntity.ok(ApiResponse.success("Login successful", "JWT_TOKEN_PLACEHOLDER")));
    }*/

    @PostMapping("/register")
    public Mono<ResponseEntity<ApiResponse<UsersDto>>> register(@RequestBody Mono<UsersDto> userDtoMono) {
        // Placeholder implementation
        return Mono.just(ResponseEntity.ok(ApiResponse.success("Registration successful", null)));
    }

}

