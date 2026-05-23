package com.fintech.loan.controller;

import com.fintech.loan.dto.request.RecordPaymentRequest;
import com.fintech.loan.dto.response.RepaymentResponse;
import com.fintech.loan.service.RepaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/loans/{loanAccountId}/repayments")
@RequiredArgsConstructor
public class RepaymentController {

    private final RepaymentService repaymentService;

    @PostMapping
    @PreAuthorize("hasRole('LOAN_OFFICER')")
    public ResponseEntity<RepaymentResponse> recordPayment(
            @PathVariable UUID loanAccountId,
            @Valid @RequestBody RecordPaymentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(repaymentService.recordPayment(loanAccountId, request));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('BORROWER', 'LOAN_OFFICER')")
    public ResponseEntity<List<RepaymentResponse>> getRepayments(@PathVariable UUID loanAccountId) {
        return ResponseEntity.ok(repaymentService.getRepayments(loanAccountId));
    }
}
