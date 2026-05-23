package com.fintech.loan.controller;

import com.fintech.loan.dto.response.AmortizationScheduleResponse;
import com.fintech.loan.dto.response.LoanAccountResponse;
import com.fintech.loan.service.LoanAccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/loans")
@RequiredArgsConstructor
public class LoanAccountController {

    private final LoanAccountService loanAccountService;

    @GetMapping("/{loanAccountId}")
    @PreAuthorize("hasAnyRole('BORROWER', 'LOAN_OFFICER')")
    public ResponseEntity<LoanAccountResponse> getLoan(@PathVariable UUID loanAccountId) {
        return ResponseEntity.ok(loanAccountService.getLoanAccount(loanAccountId));
    }

    @GetMapping("/{loanAccountId}/schedule")
    @PreAuthorize("hasAnyRole('BORROWER', 'LOAN_OFFICER')")
    public ResponseEntity<AmortizationScheduleResponse> getSchedule(@PathVariable UUID loanAccountId) {
        return ResponseEntity.ok(loanAccountService.getSchedule(loanAccountId));
    }

    @GetMapping("/borrower/{borrowerId}")
    @PreAuthorize("hasAnyRole('BORROWER', 'LOAN_OFFICER')")
    public ResponseEntity<List<LoanAccountResponse>> getLoansByBorrower(@PathVariable UUID borrowerId) {
        return ResponseEntity.ok(loanAccountService.getLoansByBorrower(borrowerId));
    }
}
