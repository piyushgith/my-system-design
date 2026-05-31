package com.test.url.shortner.url.infrastructure;

import com.test.url.shortner.url.api.UrlLookupPort;
import com.test.url.shortner.url.api.UrlLookupResult;
import com.test.url.shortner.url.domain.ShortUrl;
import com.test.url.shortner.url.domain.UrlStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class UrlLookupAdapter implements UrlLookupPort {

	private static final Logger log = LoggerFactory.getLogger(UrlLookupAdapter.class);

	private final ShortUrlRepository shortUrlRepository;
	private final UrlCacheService urlCacheService;
	private final Clock clock;

	public UrlLookupAdapter(ShortUrlRepository shortUrlRepository, UrlCacheService urlCacheService, Clock clock) {
		this.shortUrlRepository = shortUrlRepository;
		this.urlCacheService = urlCacheService;
		this.clock = clock;
	}

	@Override
	@Transactional(readOnly = true)
	public UrlLookupResult findByShortCode(String shortCode) {
		Optional<CachedUrlEntry> cached = urlCacheService.get(shortCode);
		if (cached.isPresent()) {
			log.debug("Cache hit shortCode={}", shortCode);
			return fromCachedEntry(cached.get());
		}

		log.debug("Cache miss shortCode={}", shortCode);
		Optional<ShortUrl> shortUrl = shortUrlRepository.findById(shortCode);
		if (shortUrl.isEmpty()) {
			return UrlLookupResult.notFound();
		}

		ShortUrl url = shortUrl.get();
		UrlLookupResult result = fromShortUrl(url);
		if (result.outcome() == UrlLookupResult.Outcome.FOUND) {
			urlCacheService.cache(shortCode, url.getLongUrl(), url.getExpiresAt(), url.getStatus());
		}
		return result;
	}

	private UrlLookupResult fromCachedEntry(CachedUrlEntry entry) {
		if (entry.status() == UrlStatus.DELETED) {
			return UrlLookupResult.notFound();
		}
		if (entry.status() != UrlStatus.ACTIVE) {
			return UrlLookupResult.expired();
		}
		if (entry.expiresAt() != null && !Instant.now(clock).isBefore(entry.expiresAt())) {
			return UrlLookupResult.expired();
		}
		return UrlLookupResult.found(entry.longUrl());
	}

	private UrlLookupResult fromShortUrl(ShortUrl url) {
		if (url.getStatus() == UrlStatus.DELETED) {
			return UrlLookupResult.notFound();
		}
		if (!url.isRedirectable()) {
			return UrlLookupResult.expired();
		}
		return UrlLookupResult.found(url.getLongUrl());
	}
}
