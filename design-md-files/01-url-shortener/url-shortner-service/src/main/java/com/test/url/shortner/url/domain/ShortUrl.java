package com.test.url.shortner.url.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "short_urls")
public class ShortUrl {

	@Id
	@Column(name = "short_code", length = 10, nullable = false)
	private String shortCode;

	@Column(name = "long_url", nullable = false, columnDefinition = "TEXT")
	private String longUrl;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private UrlStatus status = UrlStatus.ACTIVE;

	@Column(name = "expires_at")
	private Instant expiresAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	protected ShortUrl() {
	}

	public ShortUrl(String shortCode, String longUrl, Instant expiresAt) {
		this.shortCode = shortCode;
		this.longUrl = longUrl;
		this.expiresAt = expiresAt;
		this.status = UrlStatus.ACTIVE;
	}

	@PrePersist
	void onCreate() {
		Instant now = Instant.now();
		this.createdAt = now;
		this.updatedAt = now;
	}

	@PreUpdate
	void onUpdate() {
		this.updatedAt = Instant.now();
	}

	public boolean isRedirectable() {
		if (status != UrlStatus.ACTIVE) {
			return false;
		}
		return expiresAt == null || Instant.now().isBefore(expiresAt);
	}

	public String getShortCode() {
		return shortCode;
	}

	public String getLongUrl() {
		return longUrl;
	}

	public UrlStatus getStatus() {
		return status;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public Instant getDeletedAt() {
		return deletedAt;
	}
}
