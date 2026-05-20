package com.test.banking.core.account.application;

import com.test.banking.core.account.infrastructure.AccountRepository;
import com.test.banking.core.shared.exception.NotFoundException;
import com.test.banking.core.shared.security.AccountOwnershipLookup;
import org.springframework.stereotype.Component;

@Component
public class AccountOwnershipLookupImpl implements AccountOwnershipLookup {

    private final AccountRepository accountRepository;

    public AccountOwnershipLookupImpl(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Override
    public String findCifIdForAccount(String accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() -> new NotFoundException("Account not found: " + accountId))
                .getCifId();
    }
}
