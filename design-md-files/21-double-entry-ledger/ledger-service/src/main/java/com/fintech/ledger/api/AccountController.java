package com.fintech.ledger.api;

import com.fintech.ledger.api.dto.AccountResponse;
import com.fintech.ledger.api.dto.CreateAccountRequest;
import com.fintech.ledger.api.dto.UpdateAccountStatusRequest;
import com.fintech.ledger.service.AccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

    @PostMapping
    public ResponseEntity<AccountResponse> createAccount(@Valid @RequestBody CreateAccountRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(accountService.createAccount(req));
    }

    @GetMapping("/{accountId}")
    public ResponseEntity<AccountResponse> getAccount(@PathVariable UUID accountId) {
        return ResponseEntity.ok(accountService.getAccount(accountId));
    }

    @GetMapping
    public ResponseEntity<List<AccountResponse>> getAccountsByOwner(@RequestParam UUID ownerId) {
        return ResponseEntity.ok(accountService.getAccountsByOwner(ownerId));
    }

    @PatchMapping("/{accountId}/status")
    public ResponseEntity<AccountResponse> updateStatus(@PathVariable UUID accountId,
                                                          @Valid @RequestBody UpdateAccountStatusRequest req) {
        return ResponseEntity.ok(accountService.updateStatus(accountId, req));
    }
}
