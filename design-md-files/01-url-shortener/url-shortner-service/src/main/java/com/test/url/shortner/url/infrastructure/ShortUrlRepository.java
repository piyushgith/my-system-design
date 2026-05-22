package com.test.url.shortner.url.infrastructure;

import com.test.url.shortner.url.domain.ShortUrl;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShortUrlRepository extends JpaRepository<ShortUrl, String> {
}
