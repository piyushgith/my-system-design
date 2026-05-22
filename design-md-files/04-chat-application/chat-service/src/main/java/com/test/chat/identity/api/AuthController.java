package com.test.chat.identity.api;

import com.test.chat.identity.api.dto.AuthResponse;
import com.test.chat.identity.api.dto.LoginRequest;
import com.test.chat.identity.api.dto.RegisterRequest;
import com.test.chat.identity.application.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

	private final AuthService authService;

	public AuthController(AuthService authService) {
		this.authService = authService;
	}

	@PostMapping("/register")
	@ResponseStatus(HttpStatus.CREATED)
	public AuthResponse register(@Valid @RequestBody RegisterRequest request) {
		AuthService.AuthResult result = authService.register(
				request.username(), request.displayName(), request.email(), request.password());
		return toResponse(result);
	}

	@PostMapping("/login")
	public AuthResponse login(@Valid @RequestBody LoginRequest request) {
		AuthService.AuthResult result = authService.login(request.login(), request.password());
		return toResponse(result);
	}

	private AuthResponse toResponse(AuthService.AuthResult result) {
		return new AuthResponse(result.userId(), result.username(), result.displayName(), result.token());
	}
}
