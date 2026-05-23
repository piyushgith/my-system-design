package com.fintech.loan.controller;

import com.fintech.loan.domain.enums.ApplicationStatus;
import com.fintech.loan.dto.request.SubmitApplicationRequest;
import com.fintech.loan.dto.response.ApplicationResponse;
import com.fintech.loan.service.LoanApplicationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/applications")
@RequiredArgsConstructor
public class ApplicationController {

    private final LoanApplicationService applicationService;

    @PostMapping
    @PreAuthorize("hasAnyRole('BORROWER', 'LOAN_OFFICER')")
    public ResponseEntity<ApplicationResponse> createApplication(@Valid @RequestBody SubmitApplicationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(applicationService.createApplication(request));
    }

    @PostMapping("/{applicationId}/submit")
    @PreAuthorize("hasAnyRole('BORROWER', 'LOAN_OFFICER')")
    public ResponseEntity<ApplicationResponse> submitApplication(@PathVariable UUID applicationId) {
        return ResponseEntity.accepted().body(applicationService.submitApplication(applicationId));
    }

    @GetMapping("/{applicationId}")
    @PreAuthorize("hasAnyRole('BORROWER', 'LOAN_OFFICER')")
    public ResponseEntity<ApplicationResponse> getApplication(@PathVariable UUID applicationId) {
        return ResponseEntity.ok(applicationService.getApplication(applicationId));
    }

    @GetMapping("/borrower/{borrowerId}")
    @PreAuthorize("hasAnyRole('BORROWER', 'LOAN_OFFICER')")
    public ResponseEntity<List<ApplicationResponse>> getByBorrower(@PathVariable UUID borrowerId) {
        return ResponseEntity.ok(applicationService.getApplicationsByBorrower(borrowerId));
    }

    @GetMapping
    @PreAuthorize("hasRole('LOAN_OFFICER')")
    public ResponseEntity<Page<ApplicationResponse>> listByStatus(
            @RequestParam(required = false) ApplicationStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        if (status == null) status = ApplicationStatus.SUBMITTED;
        return ResponseEntity.ok(applicationService.getApplicationsByStatus(status, pageable));
    }
}
