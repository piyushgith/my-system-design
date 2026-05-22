package com.test.url.shortner;

import com.test.url.shortner.url.domain.UrlStatus;
import com.test.url.shortner.url.infrastructure.CachedUrlEntry;
import com.test.url.shortner.url.infrastructure.UrlCacheService;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UrlShortenerIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@MockBean
	private UrlCacheService urlCacheService;

	@Test
	void createAndRedirectUrl() throws Exception {
		when(urlCacheService.get(any())).thenReturn(Optional.empty());

		mockMvc.perform(post("/api/v1/urls")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "longUrl": "https://example.com/very/long/path",
								  "alias": "demo-link"
								}
								"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.shortCode").value("demo-link"))
				.andExpect(jsonPath("$.longUrl").value("https://example.com/very/long/path"));

		mockMvc.perform(get("/demo-link"))
				.andExpect(status().isFound())
				.andExpect(header().string("Location", "https://example.com/very/long/path"))
				.andExpect(header().string("X-Short-Code", "demo-link"));
	}

	@Test
	void rejectReservedAlias() throws Exception {
		mockMvc.perform(post("/api/v1/urls")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "longUrl": "https://example.com",
								  "alias": "api"
								}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("RESERVED_ALIAS"));
	}

	@Test
	void redirectUnknownCodeReturns404() throws Exception {
		when(urlCacheService.get(eq("unknown-code"))).thenReturn(Optional.empty());

		mockMvc.perform(get("/unknown-code"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("SHORT_URL_NOT_FOUND"));
	}

	@Test
	void redirectReturns410WhenCachedUrlIsExpired() throws Exception {
		when(urlCacheService.get(eq("expired-link"))).thenReturn(Optional.of(
				new CachedUrlEntry(
						"https://example.com/expired",
						UrlStatus.ACTIVE,
						Instant.now().minusSeconds(60))));

		mockMvc.perform(get("/expired-link"))
				.andExpect(status().isGone())
				.andExpect(jsonPath("$.error.code").value("SHORT_URL_EXPIRED"));
	}

	@Test
	void redirectReturns404WhenCachedUrlIsDeleted() throws Exception {
		when(urlCacheService.get(eq("deleted-link"))).thenReturn(Optional.of(
				new CachedUrlEntry(
						"https://example.com/deleted",
						UrlStatus.DELETED,
						null)));

		mockMvc.perform(get("/deleted-link"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("SHORT_URL_NOT_FOUND"));
	}
}
