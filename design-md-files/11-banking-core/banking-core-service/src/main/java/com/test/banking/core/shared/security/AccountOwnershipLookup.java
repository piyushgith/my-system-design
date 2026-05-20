package com.test.banking.core.shared.security;

public interface AccountOwnershipLookup {

    String findCifIdForAccount(String accountId);
}
