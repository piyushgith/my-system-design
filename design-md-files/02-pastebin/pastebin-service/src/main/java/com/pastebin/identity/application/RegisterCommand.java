package com.pastebin.identity.application;

public record RegisterCommand(String email, String password, String displayName) {
}
