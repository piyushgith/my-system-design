package com.test.chat.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {

	@Id
	@Column(name = "user_id")
	private UUID userId;

	@Column(nullable = false, unique = true, length = 50)
	private String username;

	@Column(name = "display_name", nullable = false, length = 100)
	private String displayName;

	@Column(nullable = false, unique = true)
	private String email;

	@Column(name = "password_hash", nullable = false)
	private String passwordHash;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "account_status", nullable = false, length = 10)
	private AccountStatus accountStatus = AccountStatus.ACTIVE;

	protected User() {
	}

	public User(String username, String displayName, String email, String passwordHash) {
		this.userId = UUID.randomUUID();
		this.username = username;
		this.displayName = displayName;
		this.email = email;
		this.passwordHash = passwordHash;
		this.createdAt = Instant.now();
		this.accountStatus = AccountStatus.ACTIVE;
	}

	public UUID getUserId() {
		return userId;
	}

	public String getUsername() {
		return username;
	}

	public String getDisplayName() {
		return displayName;
	}

	public String getEmail() {
		return email;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	public AccountStatus getAccountStatus() {
		return accountStatus;
	}
}
