package com.test.url.shortner.url.api;

import com.test.url.shortner.url.api.dto.CreateUrlRequest;
import com.test.url.shortner.url.api.dto.CreateUrlResponse;
import com.test.url.shortner.url.application.UrlCreationService;
import com.test.url.shortner.url.application.UrlCreationService.CreatedShortUrl;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/urls")
public class UrlController {

	private final UrlCreationService urlCreationService;

	public UrlController(UrlCreationService urlCreationService) {
		this.urlCreationService = urlCreationService;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public CreateUrlResponse create(@Valid @RequestBody CreateUrlRequest request) {
		CreatedShortUrl created = urlCreationService.create(
				request.longUrl(),
				request.alias(),
				request.ttl());
		return new CreateUrlResponse(
				created.shortUrl(),
				created.shortCode(),
				created.longUrl(),
				created.createdAt(),
				created.expiresAt());
	}
}
