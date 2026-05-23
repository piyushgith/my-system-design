package com.fintech.loan.controller;

import com.fintech.loan.dto.request.AcceptOfferRequest;
import com.fintech.loan.dto.request.UnderwritingDecisionRequest;
import com.fintech.loan.dto.response.LoanAccountResponse;
import com.fintech.loan.dto.response.LoanOfferResponse;
import com.fintech.loan.service.DisbursementService;
import com.fintech.loan.service.UnderwritingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/v1/applications/{applicationId}")
@RequiredArgsConstructor
public class UnderwritingController {

    private final UnderwritingService underwritingService;
    private final DisbursementService disbursementService;

    @PostMapping("/decision")
    @PreAuthorize("hasRole('LOAN_OFFICER')")
    public ResponseEntity<LoanOfferResponse> makeDecision(
            @PathVariable UUID applicationId,
            @Valid @RequestBody UnderwritingDecisionRequest request) {
        // TODO: extract actorId from JWT principal in V1
        UUID underwriterId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        return underwritingService.makeDecision(applicationId, underwriterId, request)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.noContent().build());
    }

    @GetMapping("/offer")
    @PreAuthorize("hasAnyRole('BORROWER', 'LOAN_OFFICER')")
    public ResponseEntity<LoanOfferResponse> getOffer(@PathVariable UUID applicationId) {
        return underwritingService.getOffer(applicationId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/offer/accept")
    @PreAuthorize("hasRole('BORROWER')")
    public ResponseEntity<LoanOfferResponse> acceptOffer(
            @PathVariable UUID applicationId,
            @Valid @RequestBody AcceptOfferRequest request) {
        // TODO: extract borrowerId from JWT principal in V1
        UUID borrowerId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        return ResponseEntity.ok(underwritingService.acceptOffer(applicationId, borrowerId, request));
    }

    @PostMapping("/disburse")
    @PreAuthorize("hasRole('LOAN_OFFICER')")
    public ResponseEntity<LoanAccountResponse> disburse(@PathVariable UUID applicationId) {
        UUID operatorId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        return ResponseEntity.ok(disbursementService.disburse(applicationId, operatorId));
    }
}
