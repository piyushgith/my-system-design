package com.fintech.loan.service;

import com.fintech.loan.domain.entity.DisbursementSaga;
import com.fintech.loan.domain.entity.LoanApplication;
import com.fintech.loan.domain.entity.LoanOffer;
import com.fintech.loan.domain.enums.ApplicationStatus;
import com.fintech.loan.domain.enums.LoanOfferStatus;
import com.fintech.loan.dto.response.LoanAccountResponse;
import com.fintech.loan.exception.InvalidStateTransitionException;
import com.fintech.loan.repository.DisbursementSagaRepository;
import com.fintech.loan.repository.LoanOfferRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class DisbursementService {

    private final LoanApplicationService applicationService;
    private final LoanOfferRepository offerRepository;
    private final LoanAccountService loanAccountService;
    private final DisbursementSagaRepository sagaRepository;
    private final AuditService auditService;

    /**
     * MVP: manual disbursement trigger. Ops team calls this after verifying funds.
     * Simulates bank transfer — real integration is a V1 concern.
     */
    @Transactional
    public LoanAccountResponse disburse(UUID applicationId, UUID operatorId) {
        LoanApplication application = applicationService.findOrThrow(applicationId);
        if (application.getStatus() != ApplicationStatus.OFFER_ACCEPTED) {
            throw new InvalidStateTransitionException("LoanApplication",
                    application.getStatus().name(), ApplicationStatus.DISBURSED.name());
        }

        LoanOffer offer = offerRepository.findByApplicationIdAndStatus(applicationId, LoanOfferStatus.ACCEPTED)
                .orElseThrow(() -> new InvalidStateTransitionException("LoanOffer", "NOT_ACCEPTED", "DISBURSE"));

        String idempotencyKey = "DISBURSE-" + applicationId;
        if (sagaRepository.existsByIdempotencyKey(idempotencyKey)) {
            // Already disbursed — return existing loan account
            return loanAccountService.getByApplicationId(applicationId);
        }

        DisbursementSaga saga = DisbursementSaga.builder()
                .offerId(offer.getOfferId())
                .status("INITIATED")
                .idempotencyKey(idempotencyKey)
                .build();
        saga = sagaRepository.save(saga);

        // Step 1: simulate ledger reservation
        saga.setLedgerReservedAt(Instant.now());
        saga.setStatus("LEDGER_RESERVED");
        sagaRepository.save(saga);

        // Step 2: simulate bank transfer
        String bankRef = "BANK-TXN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        saga.setBankTransferRef(bankRef);
        saga.setBankTransferAt(Instant.now());
        saga.setBankConfirmedAt(Instant.now());
        saga.setStatus("BANK_CONFIRMED");
        sagaRepository.save(saga);

        // Step 3: activate loan
        LoanAccountResponse account = loanAccountService.activateLoan(application, offer);
        saga.setLoanActivatedAt(Instant.now());
        saga.setStatus("COMPLETED");
        sagaRepository.save(saga);

        log.info("Loan disbursed: applicationId={}, bankRef={}, loanAccount={}",
                applicationId, bankRef, account.getLoanAccountNumber());

        auditService.log("LOAN_APPLICATION", applicationId, "DISBURSED",
                operatorId, "OPERATOR", "OFFER_ACCEPTED", "DISBURSED",
                Map.of("bankRef", bankRef, "loanAccountNumber", account.getLoanAccountNumber()), null);

        return account;
    }
}
