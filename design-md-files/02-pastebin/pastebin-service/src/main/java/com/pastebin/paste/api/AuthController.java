package com.pastebin.paste.api;

import com.pastebin.identity.application.AuthResult;
import com.pastebin.identity.application.AuthService;
import com.pastebin.identity.application.LoginCommand;
import com.pastebin.identity.application.RegisterCommand;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        AuthResult result = authService.register(new RegisterCommand(
                request.email(), request.password(), request.displayName()));
        return ResponseEntity.status(HttpStatus.CREATED).body(AuthResponse.from(result));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthResult result = authService.login(new LoginCommand(request.email(), request.password()));
        return ResponseEntity.ok(AuthResponse.from(result));
    }
}
