package com.fintech.loan.repository;

import com.fintech.loan.domain.entity.LoanApplication;
import com.fintech.loan.domain.enums.ApplicationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LoanApplicationRepository extends JpaRepository<LoanApplication, UUID> {
    List<LoanApplication> findByBorrowerId(UUID borrowerId);
    Page<LoanApplication> findByStatus(ApplicationStatus status, Pageable pageable);
}
