package com.test.url.shortner.url.application;

import com.test.url.shortner.url.domain.ShortUrl;
import com.test.url.shortner.url.domain.UrlStatus;
import com.test.url.shortner.url.infrastructure.ShortUrlRepository;
import com.test.url.shortner.url.infrastructure.UrlCacheService;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UrlCreationService {

	private static final int MAX_COLLISION_RETRIES = 5;

	private final ShortUrlRepository shortUrlRepository;
	private final ShortCodeGenerator shortCodeGenerator;
	private final LongUrlValidator longUrlValidator;
	private final AliasValidator aliasValidator;
	private final UrlCacheService urlCacheService;
	private final String baseDomain;

	public UrlCreationService(
			ShortUrlRepository shortUrlRepository,
			ShortCodeGenerator shortCodeGenerator,
			LongUrlValidator longUrlValidator,
			AliasValidator aliasValidator,
			UrlCacheService urlCacheService,
			@Value("${app.short-url.base-domain}") String baseDomain) {
		this.shortUrlRepository = shortUrlRepository;
		this.shortCodeGenerator = shortCodeGenerator;
		this.longUrlValidator = longUrlValidator;
		this.aliasValidator = aliasValidator;
		this.urlCacheService = urlCacheService;
		this.baseDomain = baseDomain;
	}

	@Transactional
	public CreatedShortUrl create(String longUrl, String alias, Long ttlSeconds) {
		longUrlValidator.validate(longUrl);

		Instant expiresAt = ttlSeconds != null ? Instant.now().plusSeconds(ttlSeconds) : null;

		if (alias != null && !alias.isBlank()) {
			aliasValidator.validate(alias);
			return persist(alias, longUrl, expiresAt, true);
		}

		for (int attempt = 0; attempt < MAX_COLLISION_RETRIES; attempt++) {
			try {
				return persist(shortCodeGenerator.generate(), longUrl, expiresAt, false);
			}
			catch (ShortCodeCollisionException ex) {
				// retry with a newly generated code
			}
		}

		throw new ShortCodeGenerationException("Unable to generate a unique short code");
	}

	private CreatedShortUrl persist(String shortCode, String longUrl, Instant expiresAt, boolean customAlias) {
		ShortUrl shortUrl = new ShortUrl(shortCode, longUrl, expiresAt);
		try {
			shortUrlRepository.saveAndFlush(shortUrl);
		}
		catch (DataIntegrityViolationException ex) {
			if (customAlias) {
				throw new AliasConflictException("The custom alias '" + shortCode + "' is already taken");
			}
			throw new ShortCodeCollisionException();
		}

		urlCacheService.cache(shortCode, longUrl, expiresAt, UrlStatus.ACTIVE);

		return new CreatedShortUrl(
				baseDomain + "/" + shortCode,
				shortCode,
				longUrl,
				shortUrl.getCreatedAt(),
				expiresAt);
	}

	public record CreatedShortUrl(
			String shortUrl,
			String shortCode,
			String longUrl,
			Instant createdAt,
			Instant expiresAt) {
	}

	public static class AliasConflictException extends RuntimeException {
		public AliasConflictException(String message) {
			super(message);
		}
	}

	public static class ShortCodeGenerationException extends RuntimeException {
		public ShortCodeGenerationException(String message) {
			super(message);
		}
	}

	static class ShortCodeCollisionException extends RuntimeException {
	}
}
