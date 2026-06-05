package com.fintech.ledger.service;

import com.fintech.ledger.api.dto.BalanceResponse;
import com.fintech.ledger.domain.Account;
import com.fintech.ledger.domain.Direction;
import com.fintech.ledger.exception.AccountNotFoundException;
import com.fintech.ledger.repository.AccountRepository;
import com.fintech.ledger.repository.JournalEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BalanceService {

    private final AccountRepository accountRepository;
    private final JournalEntryRepository journalEntryRepository;

    // MVP: balance computed from raw journal SUM (no snapshot yet)
    // V1 upgrade path: replace with snapshot + delta query
    @Transactional(readOnly = true)
    public BalanceResponse getCurrentBalance(UUID accountId) {
        Account account = findAccount(accountId);
        long balance = netBalance(
                nullToZero(journalEntryRepository.sumByAccountAndDirection(accountId, Direction.DEBIT)),
                nullToZero(journalEntryRepository.sumByAccountAndDirection(accountId, Direction.CREDIT)),
                account.getNormalBalance());
        return balanceResponse(account, balance, Instant.now());
    }

    @Transactional(readOnly = true)
    public BalanceResponse getBalanceAsOf(UUID accountId, Instant asOf) {
        Account account = findAccount(accountId);
        long balance = netBalance(
                nullToZero(journalEntryRepository.sumByAccountAndDirectionAsOf(accountId, Direction.DEBIT, asOf)),
                nullToZero(journalEntryRepository.sumByAccountAndDirectionAsOf(accountId, Direction.CREDIT, asOf)),
                account.getNormalBalance());
        return balanceResponse(account, balance, asOf);
    }

    private BalanceResponse balanceResponse(Account account, long balance, Instant asOf) {
        return new BalanceResponse(account.getAccountId(), balance, account.getCurrency(),
                account.getNormalBalance(), "COMPUTED", asOf);
    }

    private long nullToZero(Long value) {
        return value != null ? value : 0L;
    }

    // Accounting sign convention: ASSET/EXPENSE normal balance = DEBIT-positive
    // LIABILITY/EQUITY/INCOME normal balance = CREDIT-positive
    private long netBalance(long debitSum, long creditSum, Direction normalBalance) {
        return normalBalance == Direction.DEBIT
                ? debitSum - creditSum
                : creditSum - debitSum;
    }

    private Account findAccount(UUID accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
    }
}
