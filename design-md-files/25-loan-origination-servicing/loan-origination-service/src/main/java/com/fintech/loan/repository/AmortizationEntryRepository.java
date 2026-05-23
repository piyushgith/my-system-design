package com.fintech.loan.repository;

import com.fintech.loan.domain.entity.AmortizationEntry;
import com.fintech.loan.domain.enums.InstallmentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AmortizationEntryRepository extends JpaRepository<AmortizationEntry, Long> {
    List<AmortizationEntry> findByLoanAccountIdOrderByInstallmentNumber(UUID loanAccountId);
    Optional<AmortizationEntry> findByLoanAccountIdAndInstallmentNumber(UUID loanAccountId, int installmentNumber);
    Optional<AmortizationEntry> findFirstByLoanAccountIdAndStatusOrderByInstallmentNumber(UUID loanAccountId, InstallmentStatus status);
}
