package com.fintech.loan.service;

import com.fintech.loan.domain.entity.AmortizationEntry;
import com.fintech.loan.domain.entity.LoanAccount;
import com.fintech.loan.domain.entity.RepaymentRecord;
import com.fintech.loan.domain.enums.InstallmentStatus;
import com.fintech.loan.domain.enums.LoanStatus;
import com.fintech.loan.dto.request.RecordPaymentRequest;
import com.fintech.loan.dto.response.RepaymentResponse;
import com.fintech.loan.exception.DuplicatePaymentException;
import com.fintech.loan.exception.InvalidStateTransitionException;
import com.fintech.loan.repository.AmortizationEntryRepository;
import com.fintech.loan.repository.RepaymentRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RepaymentService {

    private final LoanAccountService loanAccountService;
    private final AmortizationEntryRepository amortizationEntryRepository;
    private final RepaymentRecordRepository repaymentRecordRepository;
    private final AuditService auditService;

    @Transactional
    public RepaymentResponse recordPayment(UUID loanAccountId, RecordPaymentRequest request) {
        if (repaymentRecordRepository.existsByIdempotencyKey(request.getIdempotencyKey())) {
            throw new DuplicatePaymentException(request.getIdempotencyKey());
        }

        LoanAccount account = loanAccountService.findOrThrow(loanAccountId);
        if (account.getStatus() != LoanStatus.ACTIVE) {
            throw new InvalidStateTransitionException("LoanAccount",
                    account.getStatus().name(), "PAYMENT_RECEIVED");
        }

        // Find the next scheduled installment
        AmortizationEntry nextInstallment = amortizationEntryRepository
                .findFirstByLoanAccountIdAndStatusOrderByInstallmentNumber(
                        loanAccountId, InstallmentStatus.SCHEDULED)
                .orElse(null);

        BigDecimal principalPaid;
        BigDecimal interestPaid;
        Integer installmentNumber = null;

        if (nextInstallment != null) {
            // Interest-first allocation: pay interest before touching principal
            interestPaid = nextInstallment.getInterestComponent().min(request.getAmount());
            BigDecimal rawPrincipal = request.getAmount().subtract(interestPaid).max(BigDecimal.ZERO);
            // Cap principal at outstanding to prevent recording more than was actually reduced
            principalPaid = rawPrincipal.min(account.getOutstandingPrincipal());
            installmentNumber = nextInstallment.getInstallmentNumber();

            if (request.getAmount().compareTo(nextInstallment.getEmiAmount()) >= 0) {
                nextInstallment.setStatus(InstallmentStatus.PAID);
            } else {
                nextInstallment.setStatus(InstallmentStatus.PARTIAL);
            }
            amortizationEntryRepository.save(nextInstallment);
        } else {
            // No more scheduled installments — apply fully to remaining principal (prepayment/foreclosure)
            interestPaid = BigDecimal.ZERO;
            principalPaid = request.getAmount().min(account.getOutstandingPrincipal());
        }

        // Reduce outstanding principal — guaranteed non-negative by cap above
        BigDecimal newOutstanding = account.getOutstandingPrincipal().subtract(principalPaid);
        account.setOutstandingPrincipal(newOutstanding);

        // Advance next due date
        if (nextInstallment != null && nextInstallment.getStatus() == InstallmentStatus.PAID) {
            AmortizationEntry next = amortizationEntryRepository
                    .findFirstByLoanAccountIdAndStatusOrderByInstallmentNumber(
                            loanAccountId, InstallmentStatus.SCHEDULED)
                    .orElse(null);
            account.setNextDueDate(next != null ? next.getDueDate() : null);
            account.setRemainingTenureMonths(account.getRemainingTenureMonths() - 1);
        }

        // Close loan if fully paid
        if (newOutstanding.compareTo(BigDecimal.ZERO) == 0) {
            account.setStatus(LoanStatus.CLOSED);
            account.setClosedAt(Instant.now());
            account.setClosureReason("FULL_REPAYMENT");
        }

        loanAccountService.getRepository().save(account);

        RepaymentRecord record = RepaymentRecord.builder()
                .loanAccountId(loanAccountId)
                .installmentNumber(installmentNumber)
                .amount(request.getAmount())
                .principalPaid(principalPaid)
                .interestPaid(interestPaid)
                .penaltyPaid(BigDecimal.ZERO)
                .paymentMethod(request.getPaymentMethod())
                .paymentReference(request.getPaymentReference())
                .source(request.getSource())
                .receivedAt(request.getReceivedAt())
                .appliedAt(Instant.now())
                .idempotencyKey(request.getIdempotencyKey())
                .build();
        record = repaymentRecordRepository.save(record);

        auditService.log("LOAN_ACCOUNT", loanAccountId, "PAYMENT_RECEIVED",
                null, "SYSTEM",
                LoanStatus.ACTIVE.name(),
                account.getStatus().name(),
                Map.of("amount", request.getAmount().toPlainString(),
                        "idempotencyKey", request.getIdempotencyKey()), null);

        return toResponse(record);
    }

    @Transactional(readOnly = true)
    public List<RepaymentResponse> getRepayments(UUID loanAccountId) {
        return repaymentRecordRepository.findByLoanAccountIdOrderByReceivedAtDesc(loanAccountId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    private RepaymentResponse toResponse(RepaymentRecord r) {
        return RepaymentResponse.builder()
                .repaymentId(r.getRepaymentId())
                .loanAccountId(r.getLoanAccountId())
                .installmentNumber(r.getInstallmentNumber())
                .amount(r.getAmount())
                .principalPaid(r.getPrincipalPaid())
                .interestPaid(r.getInterestPaid())
                .penaltyPaid(r.getPenaltyPaid())
                .paymentMethod(r.getPaymentMethod())
                .source(r.getSource())
                .paymentReference(r.getPaymentReference())
                .receivedAt(r.getReceivedAt())
                .appliedAt(r.getAppliedAt())
                .build();
    }
}
