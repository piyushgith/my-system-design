package com.pastebin.paste.api;

import com.pastebin.identity.application.AuthResult;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Email String email,
        @NotBlank @Size(min = 8, max = 72) String password,
        @Size(max = 100) String displayName
) {
}

record LoginRequest(
        @NotBlank @Email String email,
        @NotBlank String password
) {
}

record AuthResponse(String userId, String email, String displayName, String token) {
    static AuthResponse from(AuthResult result) {
        return new AuthResponse(result.userId(), result.email(), result.displayName(), result.token());
    }
}

record LanguageItem(String id, String label) {
    static java.util.List<LanguageItem> defaults() {
        return java.util.List.of(
                new LanguageItem("java", "Java"),
                new LanguageItem("python", "Python"),
                new LanguageItem("yaml", "YAML"),
                new LanguageItem("plaintext", "Plain Text")
        );
    }
}
