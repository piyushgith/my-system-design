package com.test.url.shortner.url.api;

public interface UrlLookupPort {

	UrlLookupResult findByShortCode(String shortCode);
}
