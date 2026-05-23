package com.fintech.loan.service;

import com.fintech.loan.domain.entity.Borrower;
import com.fintech.loan.dto.request.CreateBorrowerRequest;
import com.fintech.loan.dto.response.BorrowerResponse;
import com.fintech.loan.exception.BorrowerNotFoundException;
import com.fintech.loan.repository.BorrowerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BorrowerService {

    private final BorrowerRepository borrowerRepository;

    @Transactional
    public BorrowerResponse createBorrower(CreateBorrowerRequest request) {
        Borrower borrower = Borrower.builder()
                .externalId(UUID.randomUUID().toString())
                .fullName(request.getFullName())
                .dateOfBirth(request.getDateOfBirth())
                .panNumber(request.getPanNumber())
                .mobileNumber(request.getMobileNumber())
                .email(request.getEmail())
                .kycStatus("PENDING")
                .build();
        return toResponse(borrowerRepository.save(borrower));
    }

    @Transactional(readOnly = true)
    public BorrowerResponse getBorrower(UUID borrowerId) {
        return toResponse(borrowerRepository.findById(borrowerId)
                .orElseThrow(() -> new BorrowerNotFoundException(borrowerId)));
    }

    private BorrowerResponse toResponse(Borrower b) {
        return BorrowerResponse.builder()
                .borrowerId(b.getBorrowerId())
                .fullName(b.getFullName())
                .dateOfBirth(b.getDateOfBirth())
                .panNumber(b.getPanNumber())
                .mobileNumber(b.getMobileNumber())
                .email(b.getEmail())
                .kycStatus(b.getKycStatus())
                .createdAt(b.getCreatedAt())
                .build();
    }
}
