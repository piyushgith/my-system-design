package com.test.banking.core.account.api;

import com.test.banking.core.account.api.dto.AccountBalanceDto;

public interface AccountPublicApi {

    AccountBalanceDto lockAndGetBalance(String accountId);

    AccountBalanceDto getBalance(String accountId);

    void creditAccount(String accountId, long amountPaise);

    void debitAccount(String accountId, long amountPaise);

    void assertAccountActive(String accountId);

    String getCifIdForAccount(String accountId);
}
