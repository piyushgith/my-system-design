package com.test.chat.identity.application;

import com.test.chat.identity.domain.User;
import com.test.chat.identity.infrastructure.UserRepository;
import com.test.chat.shared.error.ChatException;
import com.test.chat.shared.security.JwtService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;
	private final JwtService jwtService;

	public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
		this.userRepository = userRepository;
		this.passwordEncoder = passwordEncoder;
		this.jwtService = jwtService;
	}

	@Transactional
	public AuthResult register(String username, String displayName, String email, String password) {
		if (userRepository.existsByUsernameIgnoreCase(username)) {
			throw ChatException.conflict("USERNAME_TAKEN", "Username is already taken");
		}
		if (userRepository.existsByEmailIgnoreCase(email)) {
			throw ChatException.conflict("EMAIL_TAKEN", "Email is already registered");
		}
		User user = new User(username, displayName, email, passwordEncoder.encode(password));
		userRepository.save(user);
		return toAuthResult(user);
	}

	@Transactional(readOnly = true)
	public AuthResult login(String login, String password) {
		User user = userRepository.findByUsernameIgnoreCase(login)
				.or(() -> userRepository.findByEmailIgnoreCase(login))
				.orElseThrow(() -> ChatException.unauthorized("Invalid credentials"));
		if (!passwordEncoder.matches(password, user.getPasswordHash())) {
			throw ChatException.unauthorized("Invalid credentials");
		}
		return toAuthResult(user);
	}

	private AuthResult toAuthResult(User user) {
		String token = jwtService.generateToken(user.getUserId());
		return new AuthResult(user.getUserId(), user.getUsername(), user.getDisplayName(), token);
	}

	public record AuthResult(
			java.util.UUID userId,
			String username,
			String displayName,
			String token
	) {
	}
}
