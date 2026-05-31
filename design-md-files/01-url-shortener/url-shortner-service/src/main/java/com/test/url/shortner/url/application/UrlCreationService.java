package com.test.url.shortner.url.application;

import com.test.url.shortner.url.api.exception.AliasConflictException;
import com.test.url.shortner.url.api.exception.ShortCodeGenerationException;
import com.test.url.shortner.url.domain.ShortUrl;
import com.test.url.shortner.url.domain.UrlStatus;
import com.test.url.shortner.url.infrastructure.ShortUrlRepository;
import com.test.url.shortner.url.infrastructure.UrlCacheService;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UrlCreationService {

	private static final Logger log = LoggerFactory.getLogger(UrlCreationService.class);
	private static final int MAX_COLLISION_RETRIES = 5;

	private final ShortUrlRepository shortUrlRepository;
	private final ShortCodeGenerator shortCodeGenerator;
	private final LongUrlValidator longUrlValidator;
	private final AliasValidator aliasValidator;
	private final UrlCacheService urlCacheService;
	private final Clock clock;
	private final String baseDomain;

	public UrlCreationService(
			ShortUrlRepository shortUrlRepository,
			ShortCodeGenerator shortCodeGenerator,
			LongUrlValidator longUrlValidator,
			AliasValidator aliasValidator,
			UrlCacheService urlCacheService,
			Clock clock,
			@Value("${app.short-url.base-domain}") String baseDomain) {
		this.shortUrlRepository = shortUrlRepository;
		this.shortCodeGenerator = shortCodeGenerator;
		this.longUrlValidator = longUrlValidator;
		this.aliasValidator = aliasValidator;
		this.urlCacheService = urlCacheService;
		this.clock = clock;
		this.baseDomain = baseDomain.endsWith("/") ? baseDomain.substring(0, baseDomain.length() - 1) : baseDomain;
	}

	@Transactional
	public CreatedShortUrl create(String longUrl, String alias, Long ttlSeconds) {
		longUrlValidator.validate(longUrl);

		Instant expiresAt = ttlSeconds != null ? Instant.now(clock).plusSeconds(ttlSeconds) : null;

		if (alias != null && !alias.isBlank()) {
			aliasValidator.validate(alias);
			return persistAlias(alias, longUrl, expiresAt)
					.orElseThrow(() -> new AliasConflictException("The custom alias '" + alias + "' is already taken"));
		}

		for (int attempt = 0; attempt < MAX_COLLISION_RETRIES; attempt++) {
			String code = shortCodeGenerator.generate();
			Optional<CreatedShortUrl> result = persistGenerated(code, longUrl, expiresAt);
			if (result.isPresent()) {
				return result.get();
			}
			log.debug("Short code collision attempt={}/{} code={}", attempt + 1, MAX_COLLISION_RETRIES, code);
		}

		throw new ShortCodeGenerationException(
				"Unable to generate a unique short code after " + MAX_COLLISION_RETRIES + " attempts");
	}

	private Optional<CreatedShortUrl> persistAlias(String alias, String longUrl, Instant expiresAt) {
		try {
			return Optional.of(save(alias, longUrl, expiresAt));
		}
		catch (DataIntegrityViolationException ex) {
			return Optional.empty();
		}
	}

	private Optional<CreatedShortUrl> persistGenerated(String shortCode, String longUrl, Instant expiresAt) {
		try {
			return Optional.of(save(shortCode, longUrl, expiresAt));
		}
		catch (DataIntegrityViolationException ex) {
			return Optional.empty();
		}
	}

	private CreatedShortUrl save(String shortCode, String longUrl, Instant expiresAt) {
		ShortUrl shortUrl = new ShortUrl(shortCode, longUrl, expiresAt);
		shortUrlRepository.saveAndFlush(shortUrl);
		urlCacheService.cache(shortCode, longUrl, expiresAt, UrlStatus.ACTIVE);
		log.info("URL created shortCode={} hasExpiry={}", shortCode, expiresAt != null);
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
}
