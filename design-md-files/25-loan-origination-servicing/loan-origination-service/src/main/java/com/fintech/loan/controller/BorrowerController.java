package com.fintech.loan.controller;

import com.fintech.loan.dto.request.CreateBorrowerRequest;
import com.fintech.loan.dto.response.BorrowerResponse;
import com.fintech.loan.service.BorrowerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/v1/borrowers")
@RequiredArgsConstructor
public class BorrowerController {

    private final BorrowerService borrowerService;

    @PostMapping
    @PreAuthorize("hasAnyRole('LOAN_OFFICER', 'BORROWER')")
    public ResponseEntity<BorrowerResponse> createBorrower(@Valid @RequestBody CreateBorrowerRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(borrowerService.createBorrower(request));
    }

    @GetMapping("/{borrowerId}")
    @PreAuthorize("hasAnyRole('LOAN_OFFICER', 'BORROWER')")
    public ResponseEntity<BorrowerResponse> getBorrower(@PathVariable UUID borrowerId) {
        return ResponseEntity.ok(borrowerService.getBorrower(borrowerId));
    }
}
