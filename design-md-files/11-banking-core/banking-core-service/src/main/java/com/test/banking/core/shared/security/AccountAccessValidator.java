package com.test.banking.core.shared.security;

import com.test.banking.core.shared.exception.ForbiddenException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class AccountAccessValidator {

    private final AccountOwnershipLookup accountOwnershipLookup;

    public AccountAccessValidator(AccountOwnershipLookup accountOwnershipLookup) {
        this.accountOwnershipLookup = accountOwnershipLookup;
    }

    public void assertCanAccessAccount(String accountId) {
        if (isTeller()) {
            return;
        }
        String principalCifId = currentPrincipalCifId();
        String accountCifId = accountOwnershipLookup.findCifIdForAccount(accountId);
        if (!principalCifId.equals(accountCifId)) {
            throw new ForbiddenException("You do not have access to account " + accountId);
        }
    }

    public void assertCanAccessCustomer(String cifId) {
        if (isTeller()) {
            return;
        }
        if (!currentPrincipalCifId().equals(cifId)) {
            throw new ForbiddenException("You do not have access to customer " + cifId);
        }
    }

    private boolean isTeller() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> "ROLE_TELLER".equals(a));
    }

    private String currentPrincipalCifId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw new ForbiddenException("Not authenticated");
        }
        return auth.getName();
    }
}
