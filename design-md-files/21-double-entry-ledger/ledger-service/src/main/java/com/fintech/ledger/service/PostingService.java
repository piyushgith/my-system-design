package com.fintech.ledger.service;

import com.fintech.ledger.api.dto.CreatePostingRequest;
import com.fintech.ledger.api.dto.PostingCreationResult;
import com.fintech.ledger.api.dto.PostingLegRequest;
import com.fintech.ledger.api.dto.PostingResponse;
import com.fintech.ledger.api.dto.ReversePostingRequest;
import com.fintech.ledger.domain.*;
import com.fintech.ledger.exception.AccountNotActiveException;
import com.fintech.ledger.exception.AccountNotFoundException;
import com.fintech.ledger.exception.PostingAlreadyReversedException;
import com.fintech.ledger.exception.PostingInvariantException;
import com.fintech.ledger.exception.PostingNotFoundException;
import com.fintech.ledger.repository.AccountRepository;
import com.fintech.ledger.repository.PostingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PostingService {

    private static final String IDEMPOTENCY_KEY_PREFIX = "idempotency:";
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);

    private final PostingRepository postingRepository;
    private final AccountRepository accountRepository;
    private final StringRedisTemplate redisTemplate;

    @Transactional
    public PostingCreationResult createPosting(CreatePostingRequest req) {
        // 1. Check Redis idempotency cache (fast short-circuit)
        String redisKey = IDEMPOTENCY_KEY_PREFIX + req.idempotencyKey();
        PostingResponse cached = resolveFromRedis(redisKey);
        if (cached != null) {
            log.debug("Idempotency HIT (Redis): {}", req.idempotencyKey());
            return new PostingCreationResult(cached, false);
        }

        // 2. DB-level idempotency check (Redis miss — DB is fallback)
        Optional<Posting> existing = postingRepository.findByIdempotencyKey(req.idempotencyKey());
        if (existing.isPresent()) {
            log.debug("Idempotency HIT (DB): {}", req.idempotencyKey());
            warmRedisCache(redisKey, existing.get().getPostingId());
            return new PostingCreationResult(PostingResponse.from(existing.get()), false);
        }

        // 3. Validate invariant: debit sum == credit sum per currency
        validateInvariant(req.legs());

        // 4. Validate all accounts exist and are ACTIVE
        List<UUID> accountIds = req.legs().stream().map(PostingLegRequest::accountId).distinct().toList();
        validateAccountIds(accountIds);

        // 5. Build and persist posting + journal entries atomically
        Posting posting = buildPosting(req);
        Posting saved = postingRepository.save(posting);

        // 6. Warm Redis cache after commit
        warmRedisCache(redisKey, saved.getPostingId());

        log.info("Posting created: {} idempotencyKey={}", saved.getPostingId(), req.idempotencyKey());
        return new PostingCreationResult(PostingResponse.from(saved), true);
    }

    @Transactional
    public PostingResponse reversePosting(UUID postingId, ReversePostingRequest req) {
        // Idempotency on reversal as well
        String redisKey = IDEMPOTENCY_KEY_PREFIX + req.idempotencyKey();
        PostingResponse cached = resolveFromRedis(redisKey);
        if (cached != null) {
            return cached;
        }

        Optional<Posting> existingReversal = postingRepository.findByIdempotencyKey(req.idempotencyKey());
        if (existingReversal.isPresent()) {
            return PostingResponse.from(existingReversal.get());
        }

        Posting original = postingRepository.findById(postingId)
                .orElseThrow(() -> new PostingNotFoundException(postingId));

        if (original.isReversed()) {
            throw new PostingAlreadyReversedException(postingId);
        }

        // Validate all accounts in original posting are still ACTIVE before reversing
        List<UUID> legAccountIds = original.getLegs().stream()
                .map(JournalEntry::getAccountId).distinct().toList();
        validateAccountIds(legAccountIds);

        // Build reversal: flip each leg's direction
        Posting reversal = new Posting();
        reversal.setIdempotencyKey(req.idempotencyKey());
        reversal.setReferenceType("REVERSAL");
        reversal.setReferenceId(postingId);
        reversal.setReversalOf(postingId);
        reversal.setEffectiveAt(req.effectiveAt());
        reversal.setCreatedAt(Instant.now());
        reversal.setDescription(req.reason() != null ? req.reason() : "Reversal of " + postingId);
        reversal.setStatus(PostingStatus.POSTED);

        for (JournalEntry originalLeg : original.getLegs()) {
            JournalEntry leg = JournalEntry.of(
                    reversal,
                    originalLeg.getAccountId(),
                    originalLeg.getDirection().opposite(),
                    originalLeg.getAmount(),
                    originalLeg.getCurrency(),
                    req.effectiveAt(),
                    "Reversal leg");
            reversal.getLegs().add(leg);
        }

        original.markReversed();
        postingRepository.save(original);
        Posting saved = postingRepository.save(reversal);

        warmRedisCache(redisKey, saved.getPostingId());
        log.info("Reversal created: {} for original={}", saved.getPostingId(), postingId);
        return PostingResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public PostingResponse getPosting(UUID postingId) {
        return postingRepository.findById(postingId)
                .map(PostingResponse::from)
                .orElseThrow(() -> new PostingNotFoundException(postingId));
    }

    @Transactional(readOnly = true)
    public Page<PostingResponse> listPostings(UUID accountId, Instant from, Instant to, int page, int size) {
        return postingRepository.findByAccountAndDateRange(accountId, from, to, PageRequest.of(page, size))
                .map(PostingResponse::from);
    }

    // Validates debit sum == credit sum per currency (the fundamental invariant)
    private void validateInvariant(List<PostingLegRequest> legs) {
        Map<String, Long> debitsByCurrency = new HashMap<>();
        Map<String, Long> creditsByCurrency = new HashMap<>();

        for (PostingLegRequest leg : legs) {
            if (leg.direction() == Direction.DEBIT) {
                debitsByCurrency.merge(leg.currency(), leg.amount(), Long::sum);
            } else {
                creditsByCurrency.merge(leg.currency(), leg.amount(), Long::sum);
            }
        }

        // Check each currency present in either side
        Set<String> allCurrencies = new HashSet<>();
        allCurrencies.addAll(debitsByCurrency.keySet());
        allCurrencies.addAll(creditsByCurrency.keySet());

        for (String currency : allCurrencies) {
            long debits = debitsByCurrency.getOrDefault(currency, 0L);
            long credits = creditsByCurrency.getOrDefault(currency, 0L);
            if (debits != credits) {
                throw new PostingInvariantException(debits, credits, currency);
            }
        }
    }

    private void validateAccountIds(List<UUID> accountIds) {
        List<Account> accounts = accountRepository.findAllByIds(accountIds);
        Map<UUID, Account> accountMap = accounts.stream()
                .collect(Collectors.toMap(Account::getAccountId, a -> a));
        for (UUID id : accountIds) {
            Account account = accountMap.get(id);
            if (account == null) throw new AccountNotFoundException(id);
            if (!account.isActive()) throw new AccountNotActiveException(id, account.getStatus());
        }
    }

    private Posting buildPosting(CreatePostingRequest req) {
        Posting posting = new Posting();
        posting.setIdempotencyKey(req.idempotencyKey());
        posting.setReferenceType(req.referenceType());
        posting.setReferenceId(req.referenceId());
        posting.setEffectiveAt(req.effectiveAt());
        posting.setCreatedAt(Instant.now());
        posting.setDescription(req.description());
        posting.setMetadata(req.metadata());
        posting.setStatus(PostingStatus.POSTED);

        for (PostingLegRequest leg : req.legs()) {
            JournalEntry entry = JournalEntry.of(
                    posting, leg.accountId(), leg.direction(),
                    leg.amount(), leg.currency(), req.effectiveAt(), leg.description());
            posting.getLegs().add(entry);
        }
        return posting;
    }

    // Resolves idempotency from Redis. Returns null on miss, corrupted value, or stale pointer.
    private PostingResponse resolveFromRedis(String redisKey) {
        try {
            String existingId = redisTemplate.opsForValue().get(redisKey);
            if (existingId == null) return null;
            UUID cachedId = UUID.fromString(existingId);
            Optional<Posting> posting = postingRepository.findById(cachedId);
            if (posting.isEmpty()) {
                // Stale pointer — evict and fall through to DB check
                redisTemplate.delete(redisKey);
                return null;
            }
            return PostingResponse.from(posting.get());
        } catch (IllegalArgumentException e) {
            log.warn("Corrupted Redis idempotency value for key {}", redisKey);
            redisTemplate.delete(redisKey);
            return null;
        } catch (Exception e) {
            // Redis unavailable — fall through to DB
            log.warn("Redis lookup failed for idempotency key {}: {}", redisKey, e.getMessage());
            return null;
        }
    }

    private void warmRedisCache(String redisKey, UUID postingId) {
        try {
            redisTemplate.opsForValue().set(redisKey, postingId.toString(), IDEMPOTENCY_TTL);
        } catch (Exception e) {
            // Redis failure must never block a successful posting
            log.warn("Failed to warm Redis idempotency cache for key {}: {}", redisKey, e.getMessage());
        }
    }
}
