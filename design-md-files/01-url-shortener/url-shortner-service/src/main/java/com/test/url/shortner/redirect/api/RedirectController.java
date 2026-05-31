package com.test.url.shortner.redirect.api;

import com.test.url.shortner.redirect.application.RedirectService;
import com.test.url.shortner.redirect.application.RedirectService.RedirectResult;
import com.test.url.shortner.shared.error.ErrorResponse;
import com.test.url.shortner.url.api.ReservedPaths;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RedirectController {

	private final RedirectService redirectService;

	public RedirectController(RedirectService redirectService) {
		this.redirectService = redirectService;
	}

	@GetMapping("/{shortCode}")
	public ResponseEntity<?> redirect(@PathVariable String shortCode) {
		if (ReservedPaths.isReserved(shortCode)) {
			return ResponseEntity.notFound().build();
		}

		RedirectResult result = redirectService.resolve(shortCode);

		return switch (result.outcome()) {
			case FOUND -> ResponseEntity.status(HttpStatus.FOUND)
					.header(HttpHeaders.LOCATION, result.longUrl())
					.header("X-Short-Code", shortCode)
					.header("X-Redirect-Type", "DIRECT")
					.header(HttpHeaders.CACHE_CONTROL, "no-store")
					.build();
			case EXPIRED -> ResponseEntity.status(HttpStatus.GONE)
					.body(new ErrorResponse("SHORT_URL_EXPIRED", "URL has expired", null));
			case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND)
					.body(new ErrorResponse("SHORT_URL_NOT_FOUND", "Short URL not found", null));
		};
	}
}
