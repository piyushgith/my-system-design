package com.test.url.shortner.url.application;

import java.security.SecureRandom;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ShortCodeGenerator {

	private static final String BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
	private static final SecureRandom RANDOM = new SecureRandom();

	private final int codeLength;

	public ShortCodeGenerator(@Value("${app.short-url.code-length:7}") int codeLength) {
		this.codeLength = codeLength;
	}

	public String generate() {
		StringBuilder builder = new StringBuilder(codeLength);
		for (int i = 0; i < codeLength; i++) {
			builder.append(BASE62.charAt(RANDOM.nextInt(BASE62.length())));
		}
		return builder.toString();
	}
}
