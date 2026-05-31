package com.test.url.shortner.redirect.application;

import com.test.url.shortner.url.api.UrlLookupPort;
import com.test.url.shortner.url.api.UrlLookupResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class RedirectService {

	private static final Logger log = LoggerFactory.getLogger(RedirectService.class);

	private final UrlLookupPort urlLookup;

	public RedirectService(UrlLookupPort urlLookup) {
		this.urlLookup = urlLookup;
	}

	public RedirectResult resolve(String shortCode) {
		UrlLookupResult lookup = urlLookup.findByShortCode(shortCode);
		RedirectResult result = switch (lookup.outcome()) {
			case FOUND -> RedirectResult.found(lookup.longUrl());
			case EXPIRED -> RedirectResult.expired();
			case NOT_FOUND -> RedirectResult.notFound();
		};
		log.debug("Redirect outcome shortCode={} outcome={}", shortCode, result.outcome());
		return result;
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
