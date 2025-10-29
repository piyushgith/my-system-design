package com.homeloan.processing.controller;


import com.homeloan.processing.entity.LoanApproval;
import com.homeloan.processing.repository.LoanApprovalRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/processing")
@Slf4j
public class LoanApprovalController {

    @Autowired
    private LoanApprovalRepository loanApprovalRepository;

    @GetMapping("/health")
    public String healthCheck() {
        log.info("Loan Approval Service is up and running.");
        return "Loan Approval Service is healthy.";
    }

    @GetMapping("/approvals/{applicationId}")
    public ResponseEntity<LoanApproval> getLoanApprovalByApplicationId(Long applicationId) {
        log.info("Fetching loan approval for application ID: {}", applicationId);
        return loanApprovalRepository.findById(applicationId).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/approvals/history/{applicationId}")
    public ResponseEntity<List<LoanApproval>> getApprovalHistoryByApplicationId(Long applicationId) {
        log.info("Fetching loan approval history for application ID: {}", applicationId);
        List<LoanApproval> approvals = loanApprovalRepository.findByApplicationIdOrderByProcessedDateDesc(applicationId);
        return ResponseEntity.ok(approvals);
    }


    @GetMapping("/approvals")
    public ResponseEntity<List<LoanApproval>> getAllLoanApprovals() {
        log.info("Fetching all loan approvals.");
        List<LoanApproval> approvals = loanApprovalRepository.findAll();
        return ResponseEntity.ok(approvals);
    }

    @GetMapping("/approvals/status/{status}")
    public ResponseEntity<List<LoanApproval>> getLoanApprovalsByStatus(LoanApproval.ApprovalStatus approvalStatus) {
        log.info("Fetching loan approvals with status: {}", approvalStatus);
        List<LoanApproval> approvals = loanApprovalRepository.findByApprovalStatus(approvalStatus);
        return ResponseEntity.ok(approvals);
    }

}
