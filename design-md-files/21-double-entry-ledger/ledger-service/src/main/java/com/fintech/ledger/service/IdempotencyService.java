package com.fintech.ledger.service;

import com.fintech.ledger.domain.Posting;
import com.fintech.ledger.repository.PostingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Owns idempotency resolution for postings: a Redis pointer cache (fast index)
 * fronting the authoritative {@code postings.idempotency_key} unique constraint.
 *
 * <p>Redis stores only {@code key -> postingId}; the full entity is always loaded
 * from the database so responses reflect committed state. Every Redis interaction
 * is best-effort — any failure falls through to the DB, which remains the
 * correctness backstop via its unique constraint.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private static final String KEY_PREFIX = "idempotency:";
    private static final Duration TTL = Duration.ofHours(24);

    private final PostingRepository postingRepository;
    private final StringRedisTemplate redisTemplate;

    /**
     * Resolves an existing posting for this idempotency key, Redis-first with a DB
     * fallback. Returns empty when the key has never been seen (caller proceeds to create).
     */
    public Optional<Posting> findExisting(String idempotencyKey) {
        Optional<Posting> fromRedis = resolveFromRedis(KEY_PREFIX + idempotencyKey);
        if (fromRedis.isPresent()) {
            log.debug("Idempotency HIT (Redis): {}", idempotencyKey);
            return fromRedis;
        }
        Optional<Posting> fromDb = postingRepository.findByIdempotencyKey(idempotencyKey);
        if (fromDb.isPresent()) {
            log.debug("Idempotency HIT (DB): {}", idempotencyKey);
            warm(idempotencyKey, fromDb.get().getPostingId()); // re-seed cache after Redis miss
        }
        return fromDb;
    }

    /**
     * Seeds the Redis pointer for a posting. Best-effort: a Redis failure here must
     * never block a successfully committed posting (the DB constraint still protects us).
     */
    public void warm(String idempotencyKey, UUID postingId) {
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + idempotencyKey, postingId.toString(), TTL);
        } catch (Exception e) {
            log.warn("Redis warm failed for idempotency key {}: {}", idempotencyKey, e.getMessage());
        }
    }

    // Returns the posting pointed to by Redis. Empty on miss, stale pointer, corruption, or Redis down.
    private Optional<Posting> resolveFromRedis(String redisKey) {
        try {
            String id = redisTemplate.opsForValue().get(redisKey);
            if (id == null) return Optional.empty();
            Optional<Posting> posting = postingRepository.findById(UUID.fromString(id));
            if (posting.isEmpty()) {
                // Stale pointer — e.g. the tx that warmed the cache later rolled back. Evict.
                redisTemplate.delete(redisKey);
            }
            return posting;
        } catch (IllegalArgumentException e) {
            log.warn("Corrupted Redis idempotency value for key {}", redisKey);
            redisTemplate.delete(redisKey);
            return Optional.empty();
        } catch (Exception e) {
            // Redis unavailable — fall through to DB.
            log.warn("Redis lookup failed for key {}: {} — falling back to DB", redisKey, e.getMessage());
            return Optional.empty();
        }
    }
}
