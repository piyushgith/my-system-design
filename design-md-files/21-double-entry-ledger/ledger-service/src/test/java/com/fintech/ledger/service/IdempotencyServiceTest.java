package com.fintech.ledger.service;

import com.fintech.ledger.domain.Posting;
import com.fintech.ledger.repository.PostingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link IdempotencyService} — the Redis-fronted, DB-backstopped
 * idempotency resolver extracted from PostingService.
 *
 * Focus: the failure-fallback matrix that justifies the extraction —
 * Redis hit, Redis miss + DB hit (re-warm), total miss, stale pointer eviction,
 * corrupted value, and Redis-down graceful degradation.
 */
@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    private static final String KEY = "txn-abc-123";
    private static final String REDIS_KEY = "idempotency:" + KEY;

    @Mock
    private PostingRepository postingRepository;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;

    @InjectMocks
    private IdempotencyService idempotencyService;

    private Posting posting;
    private UUID postingId;

    @BeforeEach
    void setUp() {
        postingId = UUID.randomUUID();
        posting = new Posting();
        posting.setPostingId(postingId);
        posting.setIdempotencyKey(KEY);
    }

    @Test
    void findExisting_redisHit_returnsPostingWithoutDbKeyLookup() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(REDIS_KEY)).thenReturn(postingId.toString());
        when(postingRepository.findById(postingId)).thenReturn(Optional.of(posting));

        Optional<Posting> result = idempotencyService.findExisting(KEY);

        assertThat(result).containsSame(posting);
        // Redis short-circuit must skip the idempotency-key DB query.
        verify(postingRepository, never()).findByIdempotencyKey(any());
    }

    @Test
    void findExisting_redisMiss_dbHit_returnsPostingAndRewarmsCache() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(REDIS_KEY)).thenReturn(null);
        when(postingRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.of(posting));

        Optional<Posting> result = idempotencyService.findExisting(KEY);

        assertThat(result).containsSame(posting);
        // Cache is re-seeded after a Redis miss so subsequent reads hit Redis.
        verify(valueOps).set(eq(REDIS_KEY), eq(postingId.toString()), any(Duration.class));
    }

    @Test
    void findExisting_totalMiss_returnsEmpty() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(REDIS_KEY)).thenReturn(null);
        when(postingRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());

        Optional<Posting> result = idempotencyService.findExisting(KEY);

        assertThat(result).isEmpty();
        verify(valueOps, never()).set(any(), any(), any(Duration.class));
    }

    @Test
    void findExisting_stalePointer_evictsAndFallsBackToDb() {
        // Redis points to a posting that does not exist (e.g. the warming tx rolled back).
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(REDIS_KEY)).thenReturn(postingId.toString());
        when(postingRepository.findById(postingId)).thenReturn(Optional.empty());
        when(postingRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());

        Optional<Posting> result = idempotencyService.findExisting(KEY);

        assertThat(result).isEmpty();
        verify(redisTemplate).delete(REDIS_KEY); // stale pointer evicted
        verify(postingRepository).findByIdempotencyKey(KEY); // fell through to DB
    }

    @Test
    void findExisting_corruptedRedisValue_evictsAndFallsBackToDb() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(REDIS_KEY)).thenReturn("not-a-uuid");
        when(postingRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.of(posting));

        Optional<Posting> result = idempotencyService.findExisting(KEY);

        assertThat(result).containsSame(posting);
        verify(redisTemplate).delete(REDIS_KEY); // corrupted value evicted
    }

    @Test
    void findExisting_redisDown_degradesGracefullyToDb() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(REDIS_KEY)).thenThrow(new RuntimeException("Redis unavailable"));
        when(postingRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.of(posting));

        Optional<Posting> result = idempotencyService.findExisting(KEY);

        assertThat(result).containsSame(posting); // DB is the correctness backstop
    }

    @Test
    void warm_redisFailure_isSwallowedAndNeverThrows() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        doThrow(new RuntimeException("Redis unavailable"))
                .when(valueOps).set(any(), any(), any(Duration.class));

        // A Redis warm failure must never block a committed posting.
        assertThatCode(() -> idempotencyService.warm(KEY, postingId)).doesNotThrowAnyException();
    }
}
