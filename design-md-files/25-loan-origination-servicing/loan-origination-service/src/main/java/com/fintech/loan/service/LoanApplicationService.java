package com.fintech.loan.service;

import com.fintech.loan.domain.entity.LoanApplication;
import com.fintech.loan.domain.enums.ApplicationStatus;
import com.fintech.loan.dto.request.SubmitApplicationRequest;
import com.fintech.loan.dto.response.ApplicationResponse;
import com.fintech.loan.exception.ApplicationNotFoundException;
import com.fintech.loan.exception.BorrowerNotFoundException;
import com.fintech.loan.exception.InvalidStateTransitionException;
import com.fintech.loan.repository.BorrowerRepository;
import com.fintech.loan.repository.LoanApplicationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class LoanApplicationService {

    private final LoanApplicationRepository applicationRepository;
    private final BorrowerRepository borrowerRepository;
    private final AuditService auditService;

    @Transactional
    public ApplicationResponse createApplication(SubmitApplicationRequest request) {
        if (!borrowerRepository.existsById(request.getBorrowerId())) {
            throw new BorrowerNotFoundException(request.getBorrowerId());
        }
        LoanApplication application = LoanApplication.builder()
                .borrowerId(request.getBorrowerId())
                .productType(request.getProductType())
                .requestedAmount(request.getRequestedAmount())
                .requestedTenureMonths(request.getRequestedTenureMonths())
                .purpose(request.getPurpose())
                .monthlyIncome(request.getMonthlyIncome())
                .status(ApplicationStatus.DRAFT)
                .build();
        application = applicationRepository.save(application);
        auditService.log("LOAN_APPLICATION", application.getApplicationId(), "CREATED",
                request.getBorrowerId(), "USER", null, "DRAFT", null, null);
        return toResponse(application);
    }

    @Transactional
    public ApplicationResponse submitApplication(UUID applicationId) {
        LoanApplication application = findOrThrow(applicationId);
        if (application.getStatus() != ApplicationStatus.DRAFT) {
            throw new InvalidStateTransitionException("LoanApplication",
                    application.getStatus().name(), ApplicationStatus.SUBMITTED.name());
        }
        application.setStatus(ApplicationStatus.SUBMITTED);
        application.setSubmittedAt(Instant.now());
        application = applicationRepository.save(application);
        auditService.log("LOAN_APPLICATION", applicationId, "SUBMITTED",
                application.getBorrowerId(), "USER", "DRAFT", "SUBMITTED", null, null);
        return toResponse(application);
    }

    @Transactional
    public void markUnderReview(UUID applicationId) {
        LoanApplication application = findOrThrow(applicationId);
        if (application.getStatus() != ApplicationStatus.SUBMITTED) {
            throw new InvalidStateTransitionException("LoanApplication",
                    application.getStatus().name(), ApplicationStatus.UNDER_REVIEW.name());
        }
        application.setStatus(ApplicationStatus.UNDER_REVIEW);
        applicationRepository.save(application);
        auditService.log("LOAN_APPLICATION", applicationId, "UNDER_REVIEW",
                null, "SYSTEM", "SUBMITTED", "UNDER_REVIEW", null, null);
    }

    @Transactional(readOnly = true)
    public ApplicationResponse getApplication(UUID applicationId) {
        return toResponse(findOrThrow(applicationId));
    }

    @Transactional(readOnly = true)
    public List<ApplicationResponse> getApplicationsByBorrower(UUID borrowerId) {
        return applicationRepository.findByBorrowerId(borrowerId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Page<ApplicationResponse> getApplicationsByStatus(ApplicationStatus status, Pageable pageable) {
        return applicationRepository.findByStatus(status, pageable).map(this::toResponse);
    }

    LoanApplication findOrThrow(UUID applicationId) {
        return applicationRepository.findById(applicationId)
                .orElseThrow(() -> new ApplicationNotFoundException(applicationId));
    }

    private ApplicationResponse toResponse(LoanApplication a) {
        return ApplicationResponse.builder()
                .applicationId(a.getApplicationId())
                .borrowerId(a.getBorrowerId())
                .productType(a.getProductType())
                .status(a.getStatus())
                .requestedAmount(a.getRequestedAmount())
                .requestedTenureMonths(a.getRequestedTenureMonths())
                .purpose(a.getPurpose())
                .monthlyIncome(a.getMonthlyIncome())
                .rejectionReason(a.getRejectionReason())
                .submittedAt(a.getSubmittedAt())
                .decidedAt(a.getDecidedAt())
                .createdAt(a.getCreatedAt())
                .build();
    }
}
