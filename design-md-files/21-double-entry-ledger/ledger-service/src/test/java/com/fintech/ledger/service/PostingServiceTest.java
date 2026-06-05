package com.fintech.ledger.service;

import com.fintech.ledger.api.dto.CreatePostingRequest;
import com.fintech.ledger.api.dto.PostingCreationResult;
import com.fintech.ledger.api.dto.PostingLegRequest;
import com.fintech.ledger.api.dto.PostingResponse;
import com.fintech.ledger.api.dto.ReversePostingRequest;
import com.fintech.ledger.domain.Account;
import com.fintech.ledger.domain.AccountStatus;
import com.fintech.ledger.domain.AccountType;
import com.fintech.ledger.domain.Direction;
import com.fintech.ledger.domain.JournalEntry;
import com.fintech.ledger.domain.Posting;
import com.fintech.ledger.domain.PostingStatus;
import com.fintech.ledger.exception.AccountNotActiveException;
import com.fintech.ledger.exception.AccountNotFoundException;
import com.fintech.ledger.exception.PostingAlreadyReversedException;
import com.fintech.ledger.exception.PostingInvariantException;
import com.fintech.ledger.repository.AccountRepository;
import com.fintech.ledger.repository.PostingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PostingService} after the IdempotencyService extraction.
 * Pins the core ledger invariants and the create/reverse flows so the refactor
 * stays behavior-preserving.
 */
@ExtendWith(MockitoExtension.class)
class PostingServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-04T00:00:00Z");

    @Mock private PostingRepository postingRepository;
    @Mock private AccountRepository accountRepository;
    @Mock private IdempotencyService idempotencyService;

    @InjectMocks private PostingService postingService;

    private UUID accountA;
    private UUID accountB;

    @BeforeEach
    void setUp() {
        accountA = UUID.randomUUID();
        accountB = UUID.randomUUID();
    }

    // ---- createPosting ----

    @Test
    void createPosting_balancedAndActive_persistsAndReportsCreated() {
        when(idempotencyService.findExisting(anyString())).thenReturn(Optional.empty());
        when(accountRepository.findAllByIds(anyList()))
                .thenReturn(List.of(account(accountA), account(accountB)));
        when(postingRepository.save(any(Posting.class))).thenAnswer(i -> {
            Posting p = i.getArgument(0);
            p.setPostingId(UUID.randomUUID());
            return p;
        });

        PostingCreationResult result = postingService.createPosting(balancedRequest("key-1"));

        assertThat(result.created()).isTrue();
        ArgumentCaptor<Posting> captor = ArgumentCaptor.forClass(Posting.class);
        verify(postingRepository).save(captor.capture());
        Posting saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(PostingStatus.POSTED);
        assertThat(saved.getLegs()).hasSize(2);
        verify(idempotencyService).warm(eq("key-1"), any(UUID.class));
    }

    @Test
    void createPosting_idempotencyHit_returnsExistingAndDoesNotPersist() {
        Posting existing = new Posting();
        existing.setPostingId(UUID.randomUUID());
        existing.setIdempotencyKey("key-1");
        existing.setStatus(PostingStatus.POSTED);
        when(idempotencyService.findExisting("key-1")).thenReturn(Optional.of(existing));

        PostingCreationResult result = postingService.createPosting(balancedRequest("key-1"));

        assertThat(result.created()).isFalse();
        assertThat(result.response().postingId()).isEqualTo(existing.getPostingId());
        verify(postingRepository, never()).save(any());
        verify(accountRepository, never()).findAllByIds(any());
    }

    @Test
    void createPosting_imbalancedLegs_throwsInvariantBeforeTouchingAccountsOrDb() {
        when(idempotencyService.findExisting(anyString())).thenReturn(Optional.empty());
        CreatePostingRequest req = request("key-1", List.of(
                leg(accountA, Direction.DEBIT, 1000L, "USD"),
                leg(accountB, Direction.CREDIT, 900L, "USD"))); // 1000 != 900

        assertThatThrownBy(() -> postingService.createPosting(req))
                .isInstanceOf(PostingInvariantException.class);

        verify(accountRepository, never()).findAllByIds(any());
        verify(postingRepository, never()).save(any());
    }

    @Test
    void createPosting_perCurrencyImbalance_throwsEvenWhenGrandTotalsMatch() {
        // USD balanced on its own, EUR balanced on its own would pass; here each currency is unbalanced.
        when(idempotencyService.findExisting(anyString())).thenReturn(Optional.empty());
        CreatePostingRequest req = request("key-1", List.of(
                leg(accountA, Direction.DEBIT, 1000L, "USD"),
                leg(accountB, Direction.CREDIT, 1000L, "EUR"))); // USD debit unmatched, EUR credit unmatched

        assertThatThrownBy(() -> postingService.createPosting(req))
                .isInstanceOf(PostingInvariantException.class);
    }

    @Test
    void createPosting_unknownAccount_throwsNotFound() {
        when(idempotencyService.findExisting(anyString())).thenReturn(Optional.empty());
        when(accountRepository.findAllByIds(anyList())).thenReturn(List.of(account(accountA))); // B missing

        assertThatThrownBy(() -> postingService.createPosting(balancedRequest("key-1")))
                .isInstanceOf(AccountNotFoundException.class);
        verify(postingRepository, never()).save(any());
    }

    @Test
    void createPosting_frozenAccount_throwsNotActive() {
        when(idempotencyService.findExisting(anyString())).thenReturn(Optional.empty());
        when(accountRepository.findAllByIds(anyList()))
                .thenReturn(List.of(account(accountA), frozenAccount(accountB)));

        assertThatThrownBy(() -> postingService.createPosting(balancedRequest("key-1")))
                .isInstanceOf(AccountNotActiveException.class);
        verify(postingRepository, never()).save(any());
    }

    // ---- reversePosting ----

    @Test
    void reversePosting_flipsEachLegDirectionAndMarksOriginalReversed() {
        Posting original = postedWithLegs();
        when(idempotencyService.findExisting(anyString())).thenReturn(Optional.empty());
        when(postingRepository.findById(original.getPostingId())).thenReturn(Optional.of(original));
        when(accountRepository.findAllByIds(anyList()))
                .thenReturn(List.of(account(accountA), account(accountB)));
        when(postingRepository.save(any(Posting.class))).thenAnswer(i -> i.getArgument(0));

        PostingResponse response = postingService.reversePosting(
                original.getPostingId(),
                new ReversePostingRequest("rev-key", "mistake", NOW));

        // Original flagged REVERSED, persisted alongside the reversal (two saves).
        assertThat(original.getStatus()).isEqualTo(PostingStatus.REVERSED);
        ArgumentCaptor<Posting> captor = ArgumentCaptor.forClass(Posting.class);
        verify(postingRepository, times(2)).save(captor.capture());
        Posting reversal = captor.getAllValues().get(1);

        assertThat(reversal.getReversalOf()).isEqualTo(original.getPostingId());
        assertThat(reversal.getStatus()).isEqualTo(PostingStatus.POSTED);
        // Leg A was DEBIT -> reversal CREDIT; leg B was CREDIT -> reversal DEBIT. Amounts preserved.
        assertThat(reversal.getLegs())
                .extracting(JournalEntry::getAccountId, JournalEntry::getDirection, JournalEntry::getAmount)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(accountA, Direction.CREDIT, 1000L),
                        org.assertj.core.groups.Tuple.tuple(accountB, Direction.DEBIT, 1000L));
        assertThat(response.reversalOf()).isEqualTo(original.getPostingId());
    }

    @Test
    void reversePosting_alreadyReversed_throws() {
        Posting original = postedWithLegs();
        original.markReversed();
        when(idempotencyService.findExisting(anyString())).thenReturn(Optional.empty());
        when(postingRepository.findById(original.getPostingId())).thenReturn(Optional.of(original));

        assertThatThrownBy(() -> postingService.reversePosting(
                original.getPostingId(), new ReversePostingRequest("rev-key", null, NOW)))
                .isInstanceOf(PostingAlreadyReversedException.class);
        verify(postingRepository, never()).save(any());
    }

    @Test
    void reversePosting_idempotencyHit_returnsExistingReversalWithoutLoadingOriginal() {
        Posting existingReversal = new Posting();
        existingReversal.setPostingId(UUID.randomUUID());
        existingReversal.setStatus(PostingStatus.POSTED);
        when(idempotencyService.findExisting("rev-key")).thenReturn(Optional.of(existingReversal));

        PostingResponse response = postingService.reversePosting(
                UUID.randomUUID(), new ReversePostingRequest("rev-key", null, NOW));

        assertThat(response.postingId()).isEqualTo(existingReversal.getPostingId());
        verify(postingRepository, never()).findById(any());
        verify(postingRepository, never()).save(any());
    }

    // ---- helpers ----

    private CreatePostingRequest balancedRequest(String key) {
        return request(key, List.of(
                leg(accountA, Direction.DEBIT, 1000L, "USD"),
                leg(accountB, Direction.CREDIT, 1000L, "USD")));
    }

    private CreatePostingRequest request(String key, List<PostingLegRequest> legs) {
        return new CreatePostingRequest(key, "TRANSFER", UUID.randomUUID(), NOW, "desc", legs, Map.of());
    }

    private PostingLegRequest leg(UUID accountId, Direction dir, long amount, String ccy) {
        return new PostingLegRequest(accountId, dir, amount, ccy, "leg");
    }

    private Posting postedWithLegs() {
        Posting p = new Posting();
        p.setPostingId(UUID.randomUUID());
        p.setStatus(PostingStatus.POSTED);
        p.getLegs().add(JournalEntry.of(p, accountA, Direction.DEBIT, 1000L, "USD", NOW, "a"));
        p.getLegs().add(JournalEntry.of(p, accountB, Direction.CREDIT, 1000L, "USD", NOW, "b"));
        return p;
    }

    private Account account(UUID id) {
        return account(id, AccountStatus.ACTIVE);
    }

    private Account frozenAccount(UUID id) {
        return account(id, AccountStatus.FROZEN);
    }

    private Account account(UUID id, AccountStatus status) {
        Account a = new Account();
        a.setAccountId(id);
        a.setAccountType(AccountType.ASSET);
        a.setNormalBalance(Direction.DEBIT);
        a.setCurrency("USD");
        a.setStatus(status);
        return a;
    }
}
