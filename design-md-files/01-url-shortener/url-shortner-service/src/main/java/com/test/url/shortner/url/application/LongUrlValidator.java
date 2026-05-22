package com.test.url.shortner.url.application;

import java.net.URI;
import java.net.URISyntaxException;
import org.springframework.stereotype.Component;

@Component
public class LongUrlValidator {

	private static final int MAX_URL_LENGTH = 2048;

	public void validate(String longUrl) {
		if (longUrl == null || longUrl.isBlank()) {
			throw new InvalidLongUrlException("Long URL is required");
		}
		if (longUrl.length() > MAX_URL_LENGTH) {
			throw new InvalidLongUrlException("Long URL exceeds maximum length of " + MAX_URL_LENGTH);
		}

		URI uri;
		try {
			uri = new URI(longUrl);
		}
		catch (URISyntaxException ex) {
			throw new InvalidLongUrlException("Long URL is not a valid URL");
		}

		String scheme = uri.getScheme();
		if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
			throw new InvalidLongUrlException("Long URL must use http or https scheme");
		}
		if (uri.getHost() == null || uri.getHost().isBlank()) {
			throw new InvalidLongUrlException("Long URL must include a host");
		}
	}

	public static class InvalidLongUrlException extends RuntimeException {
		public InvalidLongUrlException(String message) {
			super(message);
		}
	}
}
