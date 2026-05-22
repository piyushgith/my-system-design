package com.test.url.shortner.redirect.application;

import com.test.url.shortner.url.domain.ShortUrl;
import com.test.url.shortner.url.domain.UrlStatus;
import com.test.url.shortner.url.infrastructure.CachedUrlEntry;
import com.test.url.shortner.url.infrastructure.ShortUrlRepository;
import com.test.url.shortner.url.infrastructure.UrlCacheService;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedirectServiceTest {

	@Mock
	private ShortUrlRepository shortUrlRepository;

	@Mock
	private UrlCacheService urlCacheService;

	@InjectMocks
	private RedirectService redirectService;

	@Test
	void returnsFoundFromCacheWhenEntryIsActive() {
		when(urlCacheService.get("abc")).thenReturn(Optional.of(
				new CachedUrlEntry("https://example.com", UrlStatus.ACTIVE, null)));

		RedirectService.RedirectResult result = redirectService.resolve("abc");

		assertThat(result.outcome()).isEqualTo(RedirectService.RedirectOutcome.FOUND);
		assertThat(result.longUrl()).isEqualTo("https://example.com");
		verifyNoInteractions(shortUrlRepository);
	}

	@Test
	void returnsExpiredFromCacheWithoutHittingDatabase() {
		when(urlCacheService.get("abc")).thenReturn(Optional.of(
				new CachedUrlEntry("https://example.com", UrlStatus.ACTIVE, Instant.now().minusSeconds(30))));

		RedirectService.RedirectResult result = redirectService.resolve("abc");

		assertThat(result.outcome()).isEqualTo(RedirectService.RedirectOutcome.EXPIRED);
		verifyNoInteractions(shortUrlRepository);
	}

	@Test
	void returnsNotFoundFromCacheWhenDeleted() {
		when(urlCacheService.get("abc")).thenReturn(Optional.of(
				new CachedUrlEntry("https://example.com", UrlStatus.DELETED, null)));

		RedirectService.RedirectResult result = redirectService.resolve("abc");

		assertThat(result.outcome()).isEqualTo(RedirectService.RedirectOutcome.NOT_FOUND);
		verifyNoInteractions(shortUrlRepository);
	}

	@Test
	void fallsBackToDatabaseOnCacheMissAndPopulatesCache() {
		ShortUrl shortUrl = new ShortUrl("abc", "https://example.com", null);
		when(urlCacheService.get("abc")).thenReturn(Optional.empty());
		when(shortUrlRepository.findById("abc")).thenReturn(Optional.of(shortUrl));

		RedirectService.RedirectResult result = redirectService.resolve("abc");

		assertThat(result.outcome()).isEqualTo(RedirectService.RedirectOutcome.FOUND);
		verify(urlCacheService).cache("abc", "https://example.com", null, UrlStatus.ACTIVE);
	}
}
