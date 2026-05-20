package com.homeloan.documents.service;


import com.homeloan.creditcheck.events.PropertyValuationEvent;
import com.homeloan.documents.entity.DocumentVerification;
import com.homeloan.documents.events.DocumentVerificationEvent;
import com.homeloan.documents.repository.DocumentVerificationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Random;

@Slf4j
@Service
public class DocumentVerificationService {

    private final Random random = new Random();

    @Autowired
    private DocumentVerificationRepository documentVerificationRepository;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    private static final String[] VERIFIERS = {"Alice", "Bob", "Charlie", "David", "Eve"};


    @KafkaListener(topics = "document-verification-requests")
    public void handleDocumentVerificationRequest(PropertyValuationEvent event) {
        if ("PROPERTY_VALUATION_COMPLETED".equals(event.getEventType()) && "APPROVED".equals(event.getValuationStatus())) {
            log.info("Received Property Valuation Completed Event for Application ID: " + event.getApplicationId());
            performDocumentVerification(event);
        } else if ("PROPERTY_VALUATION_COMPLETED".equals(event.getEventType()) && "REJECTED".equals(event.getValuationStatus())) {
            log.info("Property Valuation Rejected for Application ID: " + event.getApplicationId() + ". Skipping document verification.");
        }
    }

    //TODO: Implement all documents verification logic later
    @Transactional
    public void performDocumentVerification(PropertyValuationEvent event) {
        try {
            //@formatter:off

            // Simulate document verification process
            String verifiedBy = VERIFIERS[random.nextInt(VERIFIERS.length)];
            // Simulate document verification logic
            DocumentVerification.VerificationStatus status = random.nextBoolean()
                    ? DocumentVerification.VerificationStatus.VERIFIED
                    : DocumentVerification.VerificationStatus.REJECTED;

            Long documentId = random.nextLong(1000L); // Example document ID;
            String documentUrl = "http://example.com/document/" + String.valueOf(documentId); // Example URL


            DocumentVerification documentVerification = DocumentVerification.builder()
                    .sagaId(event.getSagaId())
                    .applicationId(event.getApplicationId())
                    .documentId(documentId)
                    .documentType(DocumentVerification.DocumentType.INCOME_PROOF) // Example document type
                    .documentUrl(documentUrl) // Example URL
                    .verifiedBy(verifiedBy)
                    .verificationStatus(status)
                    .verificationDate(java.time.LocalDateTime.now())
                    .comments(status == DocumentVerification.VerificationStatus.VERIFIED ? "Document verified successfully." : "Document verification failed.")
                    .build();
            //@formatter:on
            documentVerificationRepository.save(documentVerification);

            //Now publish the event
            DocumentVerificationEvent.EventType eventType = DocumentVerificationEvent.EventType.DOCUMENT_VERIFICATION_COMPLETED;

            // Publish verification result event
            publishDocumentVerificationEvent(event.getSagaId(), event.getApplicationId(), documentVerification.getVerificationStatus(), eventType);

        } catch (Exception e) {
            log.error("Error during document verification for Application ID: " + event.getApplicationId(), e);
            handleDocumentVerificationFailure(event.getSagaId(), event.getApplicationId(), DocumentVerification.VerificationStatus.REJECTED, DocumentVerificationEvent.EventType.DOCUMENT_VERIFICATION_COMPENSATED, e.getMessage());
        }
    }

    private void handleDocumentVerificationFailure(String sagaId, Long applicationId, DocumentVerification.VerificationStatus verificationStatus, DocumentVerificationEvent.EventType eventType, String errorMessage) {
        //@formatter:off
        DocumentVerification documentVerification = DocumentVerification.builder()
                .sagaId(sagaId)
                .applicationId(applicationId)
                .verifiedBy("System")
                .verificationStatus(verificationStatus)
                .verificationDate(java.time.LocalDateTime.now())
                .comments("Document verification failed.")
                .build();
        //@formatter:on
        documentVerificationRepository.save(documentVerification);

        publishDocumentVerificationEvent(sagaId, applicationId, verificationStatus, eventType);
        log.info("Handled document verification failure for Application ID: " + applicationId + " with error: " + errorMessage);
    }

    private void publishDocumentVerificationEvent(String sagaId, Long applicationId, DocumentVerification.VerificationStatus verificationStatus, DocumentVerificationEvent.EventType eventType) {

        String overallStatus = (verificationStatus == DocumentVerification.VerificationStatus.VERIFIED) ? "VERIFIED" : "REJECTED";

        DocumentVerificationEvent event = DocumentVerificationEvent.builder().sagaId(sagaId).applicationId(applicationId).documentType("ALL_DOCUMENTS").verificationStatus(overallStatus).verificationDate(LocalDateTime.now()).comments("Overall document verification " + overallStatus.toLowerCase() + ".").eventType(eventType.name()).verificationStatus(verificationStatus.name()).build();

        kafkaTemplate.send("document-verification-events", event);
        log.info("Published Document Verification Event for Application ID: " + applicationId + " with Status: " + verificationStatus);
    }

}
