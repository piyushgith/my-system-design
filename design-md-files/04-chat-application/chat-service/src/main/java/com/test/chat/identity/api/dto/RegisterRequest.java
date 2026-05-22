package com.test.chat.identity.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
		@NotBlank @Size(min = 3, max = 50) String username,
		@NotBlank @Size(max = 100) String displayName,
		@NotBlank @Email String email,
		@NotBlank @Size(min = 8, max = 72) String password
) {
}
