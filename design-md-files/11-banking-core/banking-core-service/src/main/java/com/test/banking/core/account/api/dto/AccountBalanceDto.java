package com.test.banking.core.account.api.dto;

public record AccountBalanceDto(
        String accountId,
        long currentBalancePaise,
        long availableBalancePaise) {
}
