package com.fintech.loan.service;

import com.fintech.loan.domain.entity.AmortizationEntry;
import com.fintech.loan.domain.entity.LoanAccount;
import com.fintech.loan.domain.entity.LoanApplication;
import com.fintech.loan.domain.entity.LoanOffer;
import com.fintech.loan.domain.enums.ApplicationStatus;
import com.fintech.loan.domain.enums.LoanStatus;
import com.fintech.loan.dto.response.AmortizationScheduleResponse;
import com.fintech.loan.dto.response.LoanAccountResponse;
import com.fintech.loan.exception.LoanNotFoundException;
import com.fintech.loan.repository.AmortizationEntryRepository;
import com.fintech.loan.repository.LoanAccountRepository;
import com.fintech.loan.repository.LoanApplicationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class LoanAccountService {

    private final LoanAccountRepository loanAccountRepository;
    private final LoanApplicationRepository applicationRepository;
    private final AmortizationEntryRepository amortizationEntryRepository;
    private final AmortizationService amortizationService;

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional
    public LoanAccountResponse activateLoan(LoanApplication application, LoanOffer offer) {
        LocalDate disbursedDate = LocalDate.now();
        LocalDate firstDueDate = disbursedDate.plusMonths(1).withDayOfMonth(1);

        LoanAccount account = LoanAccount.builder()
                .loanAccountNumber(generateAccountNumber())
                .applicationId(application.getApplicationId())
                .borrowerId(application.getBorrowerId())
                .productType(application.getProductType())
                .status(LoanStatus.ACTIVE)
                .originalPrincipal(offer.getApprovedAmount())
                .outstandingPrincipal(offer.getApprovedAmount())
                .interestRate(offer.getInterestRate())
                .rateType("FIXED")
                .tenureMonths(offer.getTenureMonths())
                .remainingTenureMonths(offer.getTenureMonths())
                .disbursedAt(Instant.now())
                .firstDueDate(firstDueDate)
                .nextDueDate(firstDueDate)
                .emiAmount(offer.getEmiAmount())
                .build();
        account = loanAccountRepository.save(account);

        // Generate and persist amortization schedule
        List<AmortizationEntry> schedule = amortizationService.generateSchedule(
                account.getLoanAccountId(),
                offer.getApprovedAmount(),
                offer.getInterestRate(),
                offer.getTenureMonths(),
                firstDueDate);
        amortizationEntryRepository.saveAll(schedule);

        // Update application status to DISBURSED
        application.setStatus(ApplicationStatus.DISBURSED);
        applicationRepository.save(application);

        return toResponse(account);
    }

    @Transactional(readOnly = true)
    public LoanAccountResponse getLoanAccount(UUID loanAccountId) {
        LoanAccount account = loanAccountRepository.findById(loanAccountId)
                .orElseThrow(() -> new LoanNotFoundException(loanAccountId));
        return toResponse(account);
    }

    @Transactional(readOnly = true)
    public LoanAccountResponse getByApplicationId(UUID applicationId) {
        LoanAccount account = loanAccountRepository.findByApplicationId(applicationId)
                .orElseThrow(() -> new LoanNotFoundException(applicationId));
        return toResponse(account);
    }

    @Transactional(readOnly = true)
    public AmortizationScheduleResponse getSchedule(UUID loanAccountId) {
        LoanAccount account = loanAccountRepository.findById(loanAccountId)
                .orElseThrow(() -> new LoanNotFoundException(loanAccountId));
        List<AmortizationEntry> entries = amortizationEntryRepository
                .findByLoanAccountIdOrderByInstallmentNumber(loanAccountId);

        List<AmortizationScheduleResponse.InstallmentEntry> installments = entries.stream()
                .map(e -> AmortizationScheduleResponse.InstallmentEntry.builder()
                        .installmentNumber(e.getInstallmentNumber())
                        .dueDate(e.getDueDate())
                        .openingPrincipal(e.getOpeningPrincipal())
                        .emiAmount(e.getEmiAmount())
                        .principalComponent(e.getPrincipalComponent())
                        .interestComponent(e.getInterestComponent())
                        .closingPrincipal(e.getClosingPrincipal())
                        .status(e.getStatus())
                        .build())
                .collect(Collectors.toList());

        return AmortizationScheduleResponse.builder()
                .loanAccountId(loanAccountId)
                .loanAccountNumber(account.getLoanAccountNumber())
                .emiAmount(account.getEmiAmount())
                .totalInstallments(account.getTenureMonths())
                .schedule(installments)
                .build();
    }

    @Transactional(readOnly = true)
    public List<LoanAccountResponse> getLoansByBorrower(UUID borrowerId) {
        return loanAccountRepository.findByBorrowerId(borrowerId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    LoanAccount findOrThrow(UUID loanAccountId) {
        return loanAccountRepository.findById(loanAccountId)
                .orElseThrow(() -> new LoanNotFoundException(loanAccountId));
    }

    private String generateAccountNumber() {
        Long seq = ((Number) entityManager
                .createNativeQuery("SELECT nextval('loan_account_number_seq')")
                .getSingleResult()).longValue();
        String year = String.valueOf(LocalDate.now().getYear());
        return "LOAN-" + year + "-" + String.format("%06d", seq);
    }

    LoanAccountRepository getRepository() {
        return loanAccountRepository;
    }

    private LoanAccountResponse toResponse(LoanAccount a) {
        return LoanAccountResponse.builder()
                .loanAccountId(a.getLoanAccountId())
                .loanAccountNumber(a.getLoanAccountNumber())
                .borrowerId(a.getBorrowerId())
                .productType(a.getProductType())
                .status(a.getStatus())
                .originalPrincipal(a.getOriginalPrincipal())
                .outstandingPrincipal(a.getOutstandingPrincipal())
                .interestRatePercent(a.getInterestRate().multiply(java.math.BigDecimal.valueOf(100)))
                .tenureMonths(a.getTenureMonths())
                .remainingTenureMonths(a.getRemainingTenureMonths())
                .emiAmount(a.getEmiAmount())
                .firstDueDate(a.getFirstDueDate())
                .nextDueDate(a.getNextDueDate())
                .dpd(a.getDpd())
                .disbursedAt(a.getDisbursedAt())
                .createdAt(a.getCreatedAt())
                .build();
    }
}
