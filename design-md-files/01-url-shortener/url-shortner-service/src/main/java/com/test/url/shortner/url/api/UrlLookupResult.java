package com.test.url.shortner.url.api;

public record UrlLookupResult(String longUrl, Outcome outcome) {

	public enum Outcome {
		FOUND, EXPIRED, NOT_FOUND
	}

	public static UrlLookupResult found(String longUrl) {
		return new UrlLookupResult(longUrl, Outcome.FOUND);
	}

	public static UrlLookupResult expired() {
		return new UrlLookupResult(null, Outcome.EXPIRED);
	}

	public static UrlLookupResult notFound() {
		return new UrlLookupResult(null, Outcome.NOT_FOUND);
	}
}
