package com.fintech.ledger.service;

import com.fintech.ledger.api.dto.BalanceResponse;
import com.fintech.ledger.domain.Account;
import com.fintech.ledger.domain.AccountType;
import com.fintech.ledger.domain.Direction;
import com.fintech.ledger.exception.AccountNotFoundException;
import com.fintech.ledger.repository.AccountRepository;
import com.fintech.ledger.repository.JournalEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BalanceService}. Pins the accounting sign convention
 * (the easiest thing to silently invert during a refactor) and the null-sum guard.
 */
@ExtendWith(MockitoExtension.class)
class BalanceServiceTest {

    private static final Instant AS_OF = Instant.parse("2026-06-04T00:00:00Z");

    @Mock private AccountRepository accountRepository;
    @Mock private JournalEntryRepository journalEntryRepository;

    @InjectMocks private BalanceService balanceService;

    private UUID accountId;

    @BeforeEach
    void setUp() {
        accountId = UUID.randomUUID();
    }

    @Test
    void getCurrentBalance_debitNormalAccount_isDebitMinusCredit() {
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account(Direction.DEBIT)));
        when(journalEntryRepository.sumByAccountAndDirection(accountId, Direction.DEBIT)).thenReturn(1000L);
        when(journalEntryRepository.sumByAccountAndDirection(accountId, Direction.CREDIT)).thenReturn(300L);

        BalanceResponse response = balanceService.getCurrentBalance(accountId);

        assertThat(response.balance()).isEqualTo(700L); // 1000 - 300
        assertThat(response.normalBalanceDirection()).isEqualTo(Direction.DEBIT);
        assertThat(response.freshness()).isEqualTo("COMPUTED");
    }

    @Test
    void getCurrentBalance_creditNormalAccount_isCreditMinusDebit() {
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account(Direction.CREDIT)));
        when(journalEntryRepository.sumByAccountAndDirection(accountId, Direction.DEBIT)).thenReturn(300L);
        when(journalEntryRepository.sumByAccountAndDirection(accountId, Direction.CREDIT)).thenReturn(1000L);

        BalanceResponse response = balanceService.getCurrentBalance(accountId);

        assertThat(response.balance()).isEqualTo(700L); // 1000 - 300, sign flipped for credit-normal
    }

    @Test
    void getCurrentBalance_nullSums_treatedAsZero() {
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account(Direction.DEBIT)));
        when(journalEntryRepository.sumByAccountAndDirection(accountId, Direction.DEBIT)).thenReturn(null);
        when(journalEntryRepository.sumByAccountAndDirection(accountId, Direction.CREDIT)).thenReturn(null);

        BalanceResponse response = balanceService.getCurrentBalance(accountId);

        assertThat(response.balance()).isZero();
    }

    @Test
    void getBalanceAsOf_usesPointInTimeSumsAndEchoesAsOf() {
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account(Direction.DEBIT)));
        when(journalEntryRepository.sumByAccountAndDirectionAsOf(accountId, Direction.DEBIT, AS_OF)).thenReturn(500L);
        when(journalEntryRepository.sumByAccountAndDirectionAsOf(accountId, Direction.CREDIT, AS_OF)).thenReturn(200L);

        BalanceResponse response = balanceService.getBalanceAsOf(accountId, AS_OF);

        assertThat(response.balance()).isEqualTo(300L); // 500 - 200
        assertThat(response.asOf()).isEqualTo(AS_OF);
    }

    @Test
    void getCurrentBalance_unknownAccount_throwsNotFound() {
        when(accountRepository.findById(accountId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> balanceService.getCurrentBalance(accountId))
                .isInstanceOf(AccountNotFoundException.class);
    }

    private Account account(Direction normalBalance) {
        Account a = new Account();
        a.setAccountId(accountId);
        a.setAccountType(AccountType.ASSET);
        a.setNormalBalance(normalBalance);
        a.setCurrency("USD");
        return a;
    }
}
