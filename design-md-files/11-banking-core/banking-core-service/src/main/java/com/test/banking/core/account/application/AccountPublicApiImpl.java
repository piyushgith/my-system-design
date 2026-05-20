package com.test.banking.core.account.application;

import com.test.banking.core.account.api.AccountPublicApi;
import com.test.banking.core.account.api.dto.AccountBalanceDto;
import com.test.banking.core.account.infrastructure.AccountEntity;
import com.test.banking.core.account.infrastructure.AccountRepository;
import com.test.banking.core.shared.exception.BusinessRuleException;
import com.test.banking.core.shared.exception.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;

@Service
public class AccountPublicApiImpl implements AccountPublicApi {

    private final AccountRepository accountRepository;
    private final AvailableBalanceCalculator availableBalanceCalculator;

    public AccountPublicApiImpl(AccountRepository accountRepository,
                                AvailableBalanceCalculator availableBalanceCalculator) {
        this.accountRepository = accountRepository;
        this.availableBalanceCalculator = availableBalanceCalculator;
    }

    @Override
    @Transactional(readOnly = true)
    public AccountBalanceDto getBalance(String accountId) {
        AccountEntity account = load(accountId);
        availableBalanceCalculator.refreshAvailableBalance(account);
        return toDto(account);
    }

    @Override
    @Transactional
    public AccountBalanceDto lockAndGetBalance(String accountId) {
        AccountEntity account = loadForUpdate(accountId);
        availableBalanceCalculator.refreshAvailableBalance(account);
        return toDto(account);
    }

    @Override
    @Transactional
    public void creditAccount(String accountId, long amountPaise) {
        AccountEntity account = loadForUpdate(accountId);
        assertActive(account);
        account.setCurrentBalancePaise(account.getCurrentBalancePaise() + amountPaise);
        availableBalanceCalculator.refreshAvailableBalance(account);
        account.setLastTxnDate(LocalDate.now());
        account.setUpdatedAt(Instant.now());
    }

    @Override
    @Transactional
    public void debitAccount(String accountId, long amountPaise) {
        AccountEntity account = loadForUpdate(accountId);
        assertActive(account);
        availableBalanceCalculator.refreshAvailableBalance(account);
        if (account.getAvailableBalancePaise() < amountPaise) {
            throw new BusinessRuleException("INSUFFICIENT_FUNDS",
                    "Available balance is less than requested amount");
        }
        account.setCurrentBalancePaise(account.getCurrentBalancePaise() - amountPaise);
        availableBalanceCalculator.refreshAvailableBalance(account);
        account.setLastTxnDate(LocalDate.now());
        account.setUpdatedAt(Instant.now());
    }

    @Override
    @Transactional(readOnly = true)
    public void assertAccountActive(String accountId) {
        assertActive(load(accountId));
    }

    @Override
    @Transactional(readOnly = true)
    public String getCifIdForAccount(String accountId) {
        return load(accountId).getCifId();
    }

    private AccountEntity loadForUpdate(String accountId) {
        return accountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() -> new NotFoundException("Account not found: " + accountId));
    }

    private AccountEntity load(String accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() -> new NotFoundException("Account not found: " + accountId));
    }

    private void assertActive(AccountEntity account) {
        if (!"ACTIVE".equals(account.getStatus())) {
            throw new BusinessRuleException("ACCOUNT_FROZEN", "Account is not active: " + account.getStatus());
        }
    }

    private AccountBalanceDto toDto(AccountEntity account) {
        return new AccountBalanceDto(account.getAccountId(), account.getCurrentBalancePaise(),
                account.getAvailableBalancePaise());
    }
}
