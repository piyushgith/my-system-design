package com.test.banking.core.ledger.application;

import com.test.banking.core.account.application.event.AccountOpenedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class AccountOpenedEventListener {

    private final PostingService postingService;

    public AccountOpenedEventListener(PostingService postingService) {
        this.postingService = postingService;
    }

    @EventListener
    public void onAccountOpened(AccountOpenedEvent event) {
        if (event.initialDepositPaise() <= 0) {
            return;
        }
        postingService.postDeposit(
                event.accountId(),
                event.initialDepositPaise(),
                event.valueDate(),
                "Initial deposit on account opening",
                null,
                event.depositIdempotencyKey(),
                null,
                null
        );
    }
}
