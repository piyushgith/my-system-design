package com.fintech.loan.service;

import com.fintech.loan.domain.entity.LoanApplication;
import com.fintech.loan.domain.entity.LoanOffer;
import com.fintech.loan.domain.enums.ApplicationStatus;
import com.fintech.loan.domain.enums.LoanOfferStatus;
import com.fintech.loan.dto.request.UnderwritingDecisionRequest;
import com.fintech.loan.dto.response.LoanOfferResponse;
import com.fintech.loan.exception.InvalidStateTransitionException;
import com.fintech.loan.repository.LoanApplicationRepository;
import com.fintech.loan.repository.LoanOfferRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UnderwritingService {

    private static final long OFFER_VALIDITY_DAYS = 7;

    private final LoanApplicationService applicationService;
    private final LoanApplicationRepository applicationRepository;
    private final LoanOfferRepository offerRepository;
    private final AmortizationService amortizationService;
    private final AuditService auditService;

    @Transactional
    public Optional<LoanOfferResponse> makeDecision(UUID applicationId, UUID underwriterId,
                                                     UnderwritingDecisionRequest request) {
        LoanApplication application = applicationService.findOrThrow(applicationId);

        if (application.getStatus() != ApplicationStatus.SUBMITTED
                && application.getStatus() != ApplicationStatus.UNDER_REVIEW) {
            throw new InvalidStateTransitionException("LoanApplication",
                    application.getStatus().name(), "APPROVED/REJECTED");
        }

        String oldStatus = application.getStatus().name();
        application.setDecidedAt(Instant.now());

        Map<String, Object> payload = new HashMap<>();
        if (request.getBureauScore() != null) payload.put("bureauScore", request.getBureauScore());
        if (request.getDtiRatio() != null) payload.put("dtiRatio", request.getDtiRatio());
        payload.put("approved", request.getApproved());
        application.setUnderwritingPayload(payload);

        if (!request.getApproved()) {
            application.setStatus(ApplicationStatus.REJECTED);
            application.setRejectionReason(request.getRejectionReason());
            applicationRepository.save(application);
            auditService.log("LOAN_APPLICATION", applicationId, "REJECTED",
                    underwriterId, "LOAN_OFFICER", oldStatus, "REJECTED",
                    Map.of("reason", request.getRejectionReason() != null ? request.getRejectionReason() : ""), null);
            return Optional.empty();
        }

        // Guard: approval fields are required when approved=true
        if (request.getApprovedAmount() == null || request.getApprovedInterestRate() == null
                || request.getApprovedTenureMonths() == null) {
            throw new IllegalArgumentException(
                    "approvedAmount, approvedInterestRate, and approvedTenureMonths are required when approved=true");
        }

        application.setStatus(ApplicationStatus.OFFER_EXTENDED);
        applicationRepository.save(application);

        BigDecimal emi = amortizationService.calculateEmi(
                request.getApprovedAmount(),
                request.getApprovedInterestRate(),
                request.getApprovedTenureMonths());

        LoanOffer offer = LoanOffer.builder()
                .applicationId(applicationId)
                .status(LoanOfferStatus.EXTENDED)
                .approvedAmount(request.getApprovedAmount())
                .interestRate(request.getApprovedInterestRate())
                .tenureMonths(request.getApprovedTenureMonths())
                .emiAmount(emi)
                .processingFee(request.getProcessingFee())
                .validUntil(Instant.now().plus(OFFER_VALIDITY_DAYS, ChronoUnit.DAYS))
                .build();
        offer = offerRepository.save(offer);

        auditService.log("LOAN_APPLICATION", applicationId, "OFFER_EXTENDED",
                underwriterId, "LOAN_OFFICER", oldStatus, "OFFER_EXTENDED",
                Map.of("offerId", offer.getOfferId().toString(),
                        "approvedAmount", request.getApprovedAmount().toPlainString()), null);

        return Optional.of(toOfferResponse(offer));
    }

    @Transactional(readOnly = true)
    public Optional<LoanOfferResponse> getOffer(UUID applicationId) {
        return offerRepository.findByApplicationId(applicationId).map(this::toOfferResponse);
    }

    @Transactional
    public LoanOfferResponse acceptOffer(UUID applicationId, UUID borrowerId,
                                         com.fintech.loan.dto.request.AcceptOfferRequest request) {
        LoanApplication application = applicationService.findOrThrow(applicationId);
        if (application.getStatus() != ApplicationStatus.OFFER_EXTENDED) {
            throw new InvalidStateTransitionException("LoanApplication",
                    application.getStatus().name(), ApplicationStatus.OFFER_ACCEPTED.name());
        }
        LoanOffer offer = offerRepository.findByApplicationIdAndStatus(applicationId, LoanOfferStatus.EXTENDED)
                .orElseThrow(() -> new InvalidStateTransitionException("LoanOffer", "NOT_FOUND", "ACCEPTED"));

        if (offer.getValidUntil().isBefore(Instant.now())) {
            offer.setStatus(LoanOfferStatus.EXPIRED);
            offerRepository.save(offer);
            application.setStatus(ApplicationStatus.OFFER_EXPIRED);
            applicationRepository.save(application);
            throw new InvalidStateTransitionException("LoanOffer", "EXPIRED", "ACCEPTED");
        }

        offer.setStatus(LoanOfferStatus.ACCEPTED);
        offer.setAcceptedAt(Instant.now());
        offer.setDisbursementAccountNumber(request.getDisbursementAccountNumber());
        offer.setDisbursementIfsc(request.getDisbursementIfsc());
        offer.setNachConsent(request.getNachConsent());
        offer.setEsignReference(request.getEsignReference());
        offerRepository.save(offer);

        application.setStatus(ApplicationStatus.OFFER_ACCEPTED);
        applicationRepository.save(application);

        auditService.log("LOAN_APPLICATION", applicationId, "OFFER_ACCEPTED",
                borrowerId, "BORROWER", "OFFER_EXTENDED", "OFFER_ACCEPTED",
                Map.of("offerId", offer.getOfferId().toString()), null);

        return toOfferResponse(offer);
    }

    private LoanOfferResponse toOfferResponse(LoanOffer o) {
        return LoanOfferResponse.builder()
                .offerId(o.getOfferId())
                .applicationId(o.getApplicationId())
                .status(o.getStatus().name())
                .approvedAmount(o.getApprovedAmount())
                .interestRatePercent(o.getInterestRate().multiply(BigDecimal.valueOf(100)))
                .tenureMonths(o.getTenureMonths())
                .emiAmount(o.getEmiAmount())
                .processingFee(o.getProcessingFee())
                .validUntil(o.getValidUntil())
                .acceptedAt(o.getAcceptedAt())
                .createdAt(o.getCreatedAt())
                .build();
    }
}
