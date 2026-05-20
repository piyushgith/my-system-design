package com.test.banking.core.ledger.api;

import com.test.banking.core.ledger.api.dto.DepositRequest;
import com.test.banking.core.ledger.api.dto.DepositResponse;
import com.test.banking.core.ledger.api.dto.TransactionHistoryResponse;
import com.test.banking.core.ledger.api.dto.TransferRequest;
import com.test.banking.core.ledger.api.dto.TransferResponse;
import com.test.banking.core.ledger.application.TransactionService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1")
@SecurityRequirement(name = "basicAuth")
public class TransactionController {

    public static final String IDEMPOTENCY_HEADER = com.test.banking.core.shared.web.IdempotencyHeaders.IDEMPOTENCY_KEY;

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @PostMapping("/transactions/deposit")
    @PreAuthorize("hasAnyRole('TELLER', 'CUSTOMER')")
    public DepositResponse deposit(@Valid @RequestBody DepositRequest request,
                                   @RequestHeader(IDEMPOTENCY_HEADER) String idempotencyKey) {
        return transactionService.deposit(request, idempotencyKey);
    }

    @PostMapping("/transactions/transfer")
    @PreAuthorize("hasAnyRole('TELLER', 'CUSTOMER')")
    public TransferResponse transfer(@Valid @RequestBody TransferRequest request,
                                     @RequestHeader(IDEMPOTENCY_HEADER) String idempotencyKey) {
        return transactionService.transfer(request, idempotencyKey);
    }

    @GetMapping("/accounts/{accountId}/transactions")
    @PreAuthorize("hasAnyRole('TELLER', 'CUSTOMER')")
    public TransactionHistoryResponse history(
            @PathVariable String accountId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return transactionService.getHistory(accountId, fromDate, toDate, page, size);
    }
}
