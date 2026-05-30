package com.homeloan.application.repository;

import com.homeloan.application.dto.ApplicationStatus;
import com.homeloan.application.entity.LoanApplication;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;


@Repository
public interface LoanApplicationRepository extends JpaRepository<LoanApplication,Long> {

    List<LoanApplication> findByApplicationStatus(ApplicationStatus status);

    List<LoanApplication> findByApplicantEmail(String email);

    Optional<LoanApplication> findBySagaId(String sagaId);

    List<LoanApplication> findByCreatedAtBetween(java.time.LocalDateTime start, java.time.LocalDateTime end);

    @Query("SELECT COUNT(la) FROM LoanApplication la WHERE la.applicantEmail = :applicantEmail AND la.applicationStatus NOT IN ('LOAN_APPROVED','LOAN_REJECTED','CANCELLED')")
    Long countByApplicantEmailAndApplicationStatusIn(@NotNull(message = "Email is required") String applicantEmail);


}
