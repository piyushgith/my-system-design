package com.test.banking.core.account.application;

import com.test.banking.core.account.infrastructure.AccountEntity;
import com.test.banking.core.account.infrastructure.LienRepository;
import org.springframework.stereotype.Component;

@Component
public class AvailableBalanceCalculator {

    private final LienRepository lienRepository;

    public AvailableBalanceCalculator(LienRepository lienRepository) {
        this.lienRepository = lienRepository;
    }

    public long computeAvailablePaise(AccountEntity account) {
        long liens = lienRepository.sumActiveLiensPaise(account.getAccountId());
        return Math.max(0L, account.getCurrentBalancePaise() - liens);
    }

    public void refreshAvailableBalance(AccountEntity account) {
        account.setAvailableBalancePaise(computeAvailablePaise(account));
    }
}
