package com.fintech.ledger.service;

import com.fintech.ledger.api.dto.AccountResponse;
import com.fintech.ledger.api.dto.CreateAccountRequest;
import com.fintech.ledger.api.dto.UpdateAccountStatusRequest;
import com.fintech.ledger.domain.Account;
import com.fintech.ledger.domain.AccountStatus;
import com.fintech.ledger.exception.AccountNotFoundException;
import com.fintech.ledger.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountRepository accountRepository;

    @Transactional
    public AccountResponse createAccount(CreateAccountRequest req) {
        Account account = Account.create(
                req.accountCode(), req.accountName(), req.accountType(),
                req.currency(), req.ownerId(), req.ownerType());
        return AccountResponse.from(accountRepository.save(account));
    }

    @Transactional(readOnly = true)
    public AccountResponse getAccount(UUID accountId) {
        return AccountResponse.from(findById(accountId));
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> getAccountsByOwner(UUID ownerId) {
        return accountRepository.findByOwnerIdAndStatus(ownerId, AccountStatus.ACTIVE)
                .stream().map(AccountResponse::from).toList();
    }

    @Transactional
    public AccountResponse updateStatus(UUID accountId, UpdateAccountStatusRequest req) {
        Account account = findById(accountId);
        switch (req.status()) {
            case FROZEN -> account.freeze();
            case CLOSED -> account.close();
            case ACTIVE -> account.setStatus(AccountStatus.ACTIVE);
        }
        return AccountResponse.from(accountRepository.save(account));
    }

    // Package-visible for PostingService validation
    Account findById(UUID accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
    }
}
