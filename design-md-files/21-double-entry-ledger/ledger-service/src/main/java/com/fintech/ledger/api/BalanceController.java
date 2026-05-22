package com.fintech.ledger.api;

import com.fintech.ledger.api.dto.BalanceResponse;
import com.fintech.ledger.service.BalanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/accounts/{accountId}/balance")
@RequiredArgsConstructor
public class BalanceController {

    private final BalanceService balanceService;

    @GetMapping
    public ResponseEntity<BalanceResponse> getBalance(
            @PathVariable UUID accountId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant asOf) {
        BalanceResponse response = asOf != null
                ? balanceService.getBalanceAsOf(accountId, asOf)
                : balanceService.getCurrentBalance(accountId);
        return ResponseEntity.ok(response);
    }
}
