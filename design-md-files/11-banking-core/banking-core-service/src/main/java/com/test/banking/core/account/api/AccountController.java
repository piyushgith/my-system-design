package com.test.banking.core.account.api;

import com.test.banking.core.account.api.dto.AccountResponse;
import com.test.banking.core.account.api.dto.BalanceResponse;
import com.test.banking.core.account.api.dto.OpenAccountRequest;
import com.test.banking.core.account.application.AccountService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;

import static com.test.banking.core.shared.web.IdempotencyHeaders.IDEMPOTENCY_KEY;

@RestController
@RequestMapping("/api/v1/accounts")
@SecurityRequirement(name = "basicAuth")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('TELLER', 'CUSTOMER')")
    public AccountResponse openAccount(@Valid @RequestBody OpenAccountRequest request,
                                       @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey) {
        return accountService.openAccount(request, idempotencyKey);
    }

    @GetMapping("/{accountId}")
    @PreAuthorize("hasAnyRole('TELLER', 'CUSTOMER')")
    public AccountResponse getAccount(@PathVariable String accountId) {
        return accountService.getAccount(accountId);
    }

    @GetMapping("/{accountId}/balance")
    @PreAuthorize("hasAnyRole('TELLER', 'CUSTOMER')")
    public BalanceResponse getBalance(@PathVariable String accountId) {
        return accountService.getBalance(accountId);
    }
}
