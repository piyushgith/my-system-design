package com.test.url.shortner.redirect.application;

import com.test.url.shortner.url.domain.ShortUrl;
import com.test.url.shortner.url.domain.UrlStatus;
import com.test.url.shortner.url.infrastructure.CachedUrlEntry;
import com.test.url.shortner.url.infrastructure.ShortUrlRepository;
import com.test.url.shortner.url.infrastructure.UrlCacheService;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RedirectService {

	private final ShortUrlRepository shortUrlRepository;
	private final UrlCacheService urlCacheService;

	public RedirectService(ShortUrlRepository shortUrlRepository, UrlCacheService urlCacheService) {
		this.shortUrlRepository = shortUrlRepository;
		this.urlCacheService = urlCacheService;
	}

	@Transactional(readOnly = true)
	public RedirectResult resolve(String shortCode) {
		Optional<CachedUrlEntry> cached = urlCacheService.get(shortCode);
		if (cached.isPresent()) {
			return fromCachedEntry(cached.get());
		}

		Optional<ShortUrl> shortUrl = shortUrlRepository.findById(shortCode);
		if (shortUrl.isEmpty()) {
			return RedirectResult.notFound();
		}

		ShortUrl url = shortUrl.get();
		RedirectResult result = fromShortUrl(url);
		if (result.outcome() == RedirectOutcome.FOUND) {
			urlCacheService.cache(shortCode, url.getLongUrl(), url.getExpiresAt(), url.getStatus());
		}
		return result;
	}

	private RedirectResult fromCachedEntry(CachedUrlEntry entry) {
		if (entry.status() == UrlStatus.DELETED) {
			return RedirectResult.notFound();
		}
		if (!entry.isRedirectable()) {
			return RedirectResult.expired();
		}
		return RedirectResult.found(entry.longUrl());
	}

	private RedirectResult fromShortUrl(ShortUrl url) {
		if (url.getStatus() == UrlStatus.DELETED) {
			return RedirectResult.notFound();
		}
		if (url.getStatus() == UrlStatus.EXPIRED || !url.isRedirectable()) {
			return RedirectResult.expired();
		}
		return RedirectResult.found(url.getLongUrl());
	}

	public enum RedirectOutcome {
		FOUND,
		NOT_FOUND,
		EXPIRED
	}

	public record RedirectResult(RedirectOutcome outcome, String longUrl) {

		public static RedirectResult found(String longUrl) {
			return new RedirectResult(RedirectOutcome.FOUND, longUrl);
		}

		public static RedirectResult notFound() {
			return new RedirectResult(RedirectOutcome.NOT_FOUND, null);
		}

		public static RedirectResult expired() {
			return new RedirectResult(RedirectOutcome.EXPIRED, null);
		}
	}
}
