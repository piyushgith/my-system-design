package com.example.tiny.url.service;

import com.example.tiny.url.entiry.ShortenedUrl;
import com.example.tiny.url.repository.ShortenedUrlRepository;
import com.example.tiny.url.service.util.UrlShortener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Service
public class ShortenedUrlService {

    @Autowired
    private ShortenedUrlRepository repository;

    @Autowired
    private UrlShortener urlShortener;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

//    @Autowired
//    private StringRedisTemplate stringRedisTemplate;

    @Value("${app.url.prefix}")
    private String baseurl;

    //@Transactional(propagation = Propagation.REQUIRES_NEW,
    // isolation = Isolation.SERIALIZABLE)

    //Best for high write scenarios
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public String shortenUrl(String longUrl) {
        // 1. Generate unique shortCode
        // NOTE: This call must ensure uniqueness, often by checking the database
        // repeatedly until a unique code is generated before starting the transaction.
        String shortCode = urlShortener.generateUniqueShortCode(longUrl);

        //check if shortCode already exists
        Optional<ShortenedUrl> shortenedUrlOptional = repository.findByShortCode(shortCode);

        if (!shortenedUrlOptional.isPresent()) {

            // 2. Save mapping to the relational database
            ShortenedUrl shortenedUrl = new ShortenedUrl();
            shortenedUrl.setLongUrl(longUrl);
            shortenedUrl.setShortCode(shortCode);

            // The repository.save() is within the transaction, ensuring that if a
            // database constraint (like a unique index on shortCode) is violated,
            // the transaction will roll back.
            repository.save(shortenedUrl);

            // 3. PUSH TO REDIS FOR CACHING (Key-Value Store)
            // We should include a Time-To-Live (TTL) to prevent indefinite cache growth.
            long CACHE_TTL_MINUTES = 5;

            // Key: The short code Value: The long URL
            // Use the .set(key, value, timeout, unit) method for atomic set-with-expiry
            redisTemplate.opsForValue().set(shortCode, longUrl, CACHE_TTL_MINUTES, TimeUnit.MINUTES);
        }

        // 4. Return the full short URL
        return (baseurl + shortCode).trim();
    }


    /**
     * Retrieves the long URL, prioritizing the Redis cache.
     */
    public Optional<ShortenedUrl> getLongUrlByShortCode(String shortCode) {
        // 1. Check Redis cache first
        String longUrl = redisTemplate.opsForValue().get(shortCode);

        if (longUrl != null) {
            // 2. Found in cache: Create and return a ShortenedUrl object
            //Note: We only store the longUrl in Redis, so we construct the object here.
            ShortenedUrl cachedUrl = new ShortenedUrl();
            cachedUrl.setShortCode(shortCode);
            cachedUrl.setLongUrl(longUrl.replaceAll("\"", ""));
            return Optional.of(cachedUrl);
        }

        // 3. Not found in cache: Query the relational database
        Optional<ShortenedUrl> shortenedUrlOptional = repository.findByShortCode(shortCode);

        // 4. If found in DB, populate the cache (Write-through)
        shortenedUrlOptional.ifPresent(shortenedUrl -> {
            // Set the longUrl in Redis for future requests
            redisTemplate.opsForValue().set(
                    shortCode,
                    shortenedUrl.getLongUrl(),
                    5, // Time-To-Live (TTL) in minutes/days
                    TimeUnit.MINUTES
            );
        });

        // 5. Return the result from the database (either found or empty)
        return shortenedUrlOptional;
    }
}
