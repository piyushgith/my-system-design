package com.test.banking.core.account.application.event;

import java.time.LocalDate;

public record AccountOpenedEvent(
        String accountId,
        long initialDepositPaise,
        String depositIdempotencyKey,
        LocalDate valueDate) {
}
