package com.fintech.loan.repository;

import com.fintech.loan.domain.entity.LoanAccount;
import com.fintech.loan.domain.enums.LoanStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LoanAccountRepository extends JpaRepository<LoanAccount, UUID> {
    List<LoanAccount> findByBorrowerIdAndStatus(UUID borrowerId, LoanStatus status);
    List<LoanAccount> findByBorrowerId(UUID borrowerId);
    Optional<LoanAccount> findByApplicationId(UUID applicationId);
    Optional<LoanAccount> findByLoanAccountNumber(String loanAccountNumber);
}
