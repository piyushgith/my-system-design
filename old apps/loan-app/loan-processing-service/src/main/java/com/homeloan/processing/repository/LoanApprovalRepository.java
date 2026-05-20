package com.homeloan.processing.repository;

import com.homeloan.processing.entity.LoanApproval;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;


@Repository
public interface LoanApprovalRepository extends JpaRepository<LoanApproval, Long> {

    Optional<LoanApproval> findByApplicationId(Long applicationId);

    Optional<LoanApproval> findBySagaId(String sagaId);

    List<LoanApproval> findByApprovalStatus(LoanApproval.ApprovalStatus approvalStatus);

    List<LoanApproval> findByProcessedBy(String processedBy);

    List<LoanApproval> findByApplicationIdOrderByProcessedDateDesc(Long applicationId);
}
