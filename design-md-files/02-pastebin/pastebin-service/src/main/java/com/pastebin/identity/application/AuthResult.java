package com.pastebin.identity.application;

public record AuthResult(String userId, String email, String displayName, String token) {
}
