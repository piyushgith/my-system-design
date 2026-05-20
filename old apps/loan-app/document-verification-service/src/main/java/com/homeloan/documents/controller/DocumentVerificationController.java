package com.homeloan.documents.controller;


import com.homeloan.documents.entity.DocumentVerification;
import com.homeloan.documents.repository.DocumentVerificationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/documents")
public class DocumentVerificationController {

    @Autowired
    private DocumentVerificationRepository documentVerificationRepository;

    @GetMapping("/health")
    public ResponseEntity<String> healthCheck() {
        log.info("Health check endpoint called");
        return ResponseEntity.ok("Document Verification Service is up and running!");
    }

    @GetMapping("/verifications")
    public ResponseEntity<List<DocumentVerification>> getAllVerifications() {
        log.info("Fetching all document verifications");
        List<DocumentVerification> verifications = documentVerificationRepository.findAll();
        return ResponseEntity.ok(verifications);
    }

    @GetMapping("/verifications/{applicationId}")
    public ResponseEntity<List<DocumentVerification>> getVerificationsByApplicationId(@PathVariable Long applicationId) {
        log.info("Fetching document verifications for applicationId: {}", applicationId);
        List<DocumentVerification> verifications = documentVerificationRepository.findByApplicationId(applicationId);
        return ResponseEntity.ok(verifications);
    }

    @GetMapping("/verifications/history/{applicantId}")
    public ResponseEntity<List<DocumentVerification>> getVerificationHistoryByApplicantId(@PathVariable Long applicantId) {
        log.info("Fetching document verification history for applicantId: {}", applicantId);
        List<DocumentVerification> verifications = documentVerificationRepository.findByApplicationIdOrderByVerificationDateDesc(applicantId);
        return ResponseEntity.ok(verifications);
    }

    @GetMapping("/verifications/verified/{applicantId}")
    public ResponseEntity<List<DocumentVerification>> getVerifiedDocumentsByApplicantId(@PathVariable Long applicantId) {
        log.info("Fetching verified documents for applicantId: {}", applicantId);
        List<DocumentVerification> verifications = documentVerificationRepository.findVerifiedDocumentsByApplicationId(applicantId);
        return ResponseEntity.ok(verifications);
    }

}
