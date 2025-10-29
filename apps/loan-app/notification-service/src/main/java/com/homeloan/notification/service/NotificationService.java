package com.homeloan.notification.service;


import com.homeloan.notification.event.CreditCheckEvent;
import com.homeloan.notification.event.DocumentVerificationEvent;
import com.homeloan.notification.event.LoanApplicationEvent;
import com.homeloan.notification.event.PropertyValuationEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class NotificationService {

    @KafkaListener(topics = "loan-application-events")
    public void handleLoanApplicationEvent(LoanApplicationEvent message) {
        // Process the event (e.g., send notification)
        if ("LOAN_APPLICATION_CREATED".equals(message.getEventType())) {
            log.info("Sending notification for loan application created: Application ID {}", message.getApplicationId());
            // Add notification logic here
            sendApplicationSubmissionNotification(message);
        }
    }

    @KafkaListener(topics = "credit-check-events")
    public void handleCreditCheckEvent(CreditCheckEvent message) {
        // Process credit check events
        log.info("Received credit check event: {}", message);
        if ("CREDIT_CHECK_COMPLETED".equals(message.getEventType())) {
            // Add notification logic here
            log.info("Sending notification for credit check completed");
            sendCreditCheckNotification(message);
        }
    }

    @KafkaListener(topics = "property-valuation-events")
    public void handlePropertyValuationEvent(PropertyValuationEvent event) {
        log.info("Received property valuation event: {}", event.getApplicationId());
        if ("PROPERTY_VALUATION_COMPLETED".equals(event.getEventType())) {
            // Add notification logic here
            log.info("Sending notification for property valuation completed");
            sendPropertyValuationNotification(event);
        }
    }


    @KafkaListener(topics = "document-verification-events")
    public void handleDocumentVerificationEvent(DocumentVerificationEvent event) {
        // Process document verification events
        log.info("Received document verification event: {}", event.getApplicationId());
        if ("DOCUMENT_VERIFICATION_COMPLETED".equals(event.getEventType())) {
            log.info("Sending notification for document verification completed");
            sendDocumentVerificationNotification(event);
        }
    }

    @KafkaListener(topics = "loan-processing-events")
    public void handleLoanProcessingEvent(LoanApplicationEvent event) {
        log.info("Received loan processing event: {}", event.getApplicationId());
        if ("LOAN_PROCESSING_COMPLETED".equals(event.getEventType())) {
            log.info("Sending notification for loan processing completed");
            sendLoanProcessingNotification(event);
        } else if ("LOAN_PROCESSING_FAILED".equals(event.getEventType())) {
            log.info("Sending notification for loan processing failed");
            sendLoanProcessingFailureNotification(event);
        }
    }

    private void sendLoanProcessingFailureNotification(LoanApplicationEvent event) {
        log.info("Loan processing failure notification sent: {}", event.getApplicationId());
    }

    private void sendLoanProcessingNotification(LoanApplicationEvent event) {
        log.info("Loan processing notification sent: {}", event.getApplicationId());
    }


    private void sendDocumentVerificationNotification(DocumentVerificationEvent event) {
        log.info("Document verification notification sent: {}", event.getApplicationId());
    }


    private void sendPropertyValuationNotification(PropertyValuationEvent message) {
        log.info("Property valuation notification sent: {}", message.getApplicationId());
    }

    private void sendCreditCheckNotification(CreditCheckEvent message) {
        // Placeholder for notification logic
        log.info("Credit check notification sent: {}", message.getApplicationId());
    }


    private void sendApplicationSubmissionNotification(LoanApplicationEvent event) {
        // Placeholder for notification logic
        log.info("Notification sent to {} for application ID {}", event.getApplicantEmail(), event.getApplicationId());
    }


}
