package com.homeloan.processing.service;


import com.homeloan.processing.entity.LoanApproval;
import com.homeloan.processing.events.DocumentVerificationEvent;
import com.homeloan.processing.events.LoanProcessingEvent;
import com.homeloan.processing.repository.LoanApprovalRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Random;

@Service
@Slf4j
public class LoanApprovalService {

    @Autowired
    private LoanApprovalRepository loanApprovalRepository;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    private final Random random = new Random();

    @KafkaListener(topics = "loan-application-topic")
    public void processLoanApplication(DocumentVerificationEvent event) {
        log.info("Received loan application: {}", event.getApplicationId());
        // Process the loan application (e.g., validate, check credit score, etc.)
        if ("DOCUMENT_VERIFICATION_COMPLETED".equals(event.getEventType())
                && "VERIFIED".equals(event.getVerificationStatus())) {
            log.info("Loan application {} processing based on document verification status: {}", event.getApplicationId(), event.getVerificationStatus());
            processLoanApproval(event);
        } else if ("DOCUMENT_VERIFICATION_COMPLETED".equals(event.getEventType())
                && "REJECTED".equals(event.getVerificationStatus())) {
            log.warn("Loan application {} cannot be processed due to document verification status: {}", event.getApplicationId(), event.getVerificationStatus());
            rejectLoanApplication(event);
        }
    }

    @Transactional
    private void processLoanApproval(DocumentVerificationEvent event) {
        try {
            Thread.sleep(3000L + random.nextInt(5000));

            // Simulate loan approval logic
            log.info("Loan application {} approved.", event.getApplicationId());
            // Update loan approval status in the database
            LoanApproval approval = getLoanApproval(event);
            loanApprovalRepository.save(approval);
            // Publish loan approval event

            LoanProcessingEvent.EventType eventType = approval.getApprovalStatus() == LoanApproval.ApprovalStatus.APPROVED ?
                    LoanProcessingEvent.EventType.LOAN_PROCESSING_COMPLETED :
                    LoanProcessingEvent.EventType.LOAN_PROCESSING_FAILED;

            publishLoanProcessingEvent(approval, eventType);

            log.info("Published loan processing event for application {} Status {}, Amount =${}.",
                    event.getApplicationId(),approval.getApprovalStatus().name(), approval.getApprovedAmount());
        } catch (InterruptedException e) {
            //throw new RuntimeException(e);
            handleLoanApprovalFailure(event.getApplicationId(),event.getSagaId(),"Loan approval interrupted");
        } catch (Exception e) {
            //throw new RuntimeException(e);
            handleLoanApprovalFailure(event.getApplicationId(),event.getSagaId(),e.getMessage());
        }
    }



    private LoanApproval getLoanApproval(DocumentVerificationEvent event) {
        BigDecimal loanAmount = generateLoanAmount();
        BigDecimal interestRate = generateInterestRate();
        Integer loanTerm = generateLoanTerm();
        // Calculate EMI
        BigDecimal emiAmount = calculateEMI(loanAmount, interestRate, loanTerm);

        LoanApproval approval = LoanApproval.builder()
                .applicationId(event.getApplicationId())
                .approvalStatus(LoanApproval.ApprovalStatus.APPROVED)
                .interestRate(interestRate)
                .loanTermMonths(loanTerm)
                .monthlyPayment(emiAmount)
                .approvedAmount(loanAmount)
                .processedBy("System")
                .sagaId(event.getSagaId())
                .loanConditions(generateLoanConditions(interestRate))
                .build();

        return approval;
    }


    private String generateLoanConditions(BigDecimal interestRate) {
        if (interestRate.compareTo(BigDecimal.valueOf(5.0)) < 0) {
            return "Standard conditions apply.";
        } else {
            return "Higher interest rate due to credit risk. Collateral required.";
        }
    }


    private void rejectLoanApplication(DocumentVerificationEvent event) {
        log.info("Rejecting loan application: {}", event.getApplicationId());
        // Implement rejection logic (e.g., update status in DB, notify user, etc.)
        LoanApproval rejection = LoanApproval.builder()
                .applicationId(event.getApplicationId())
                .approvalStatus(LoanApproval.ApprovalStatus.REJECTED)
                .approvedAmount(BigDecimal.ZERO)
                .interestRate(BigDecimal.ZERO)
                .loanTermMonths(0)
                .monthlyPayment(BigDecimal.ZERO)
                .processedBy("System")
                .rejectionReason("Document verification failed.")
                .sagaId(event.getSagaId())
                .build();
        rejection = loanApprovalRepository.save(rejection);
        publishLoanProcessingEvent(rejection, LoanProcessingEvent.EventType.LOAN_PROCESSING_FAILED);
        log.info("Published loan rejection event for application {}.", event.getApplicationId());
    }


    private BigDecimal generateLoanAmount() {
        double amount = 100000 + (450000 * random.nextDouble());
        return BigDecimal.valueOf(amount).setScale(0, BigDecimal.ROUND_HALF_UP);
    }

    private BigDecimal generateInterestRate() {
        // Interest rate between 3.5% and 7.5%
        double rate = 3.5 + (4.0 * random.nextDouble());
        return BigDecimal.valueOf(rate).setScale(2, BigDecimal.ROUND_HALF_UP);
    }

    private Integer generateLoanTerm() {
        // Loan term between 15 and 30 years
        Integer[] terms = {15, 20, 25, 30};
        return terms[random.nextInt(terms.length)];
    }

    //calculate EMI
    private BigDecimal calculateEMI(BigDecimal principal, BigDecimal annualInterestRate, Integer termYears) {
        BigDecimal monthlyInterestRate = annualInterestRate.divide(BigDecimal.valueOf(1200), BigDecimal.ROUND_HALF_UP);
        int totalPayments = termYears * 12;
        BigDecimal numerator = principal.multiply(monthlyInterestRate).multiply((BigDecimal.ONE.add(monthlyInterestRate)).pow(totalPayments));
        BigDecimal denominator = (BigDecimal.ONE.add(monthlyInterestRate)).pow(totalPayments).subtract(BigDecimal.ONE);
        return numerator.divide(denominator, BigDecimal.ROUND_HALF_UP).setScale(2, BigDecimal.ROUND_HALF_UP);
    }




    private void handleLoanApprovalFailure(Long applicationId, String sagaId, String message) {
        LoanApproval approval = LoanApproval.builder()
                .applicationId(applicationId)
                .approvalStatus(LoanApproval.ApprovalStatus.REJECTED)
                .approvedAmount(BigDecimal.ZERO)
                .interestRate(BigDecimal.ZERO)
                .loanTermMonths(0)
                .monthlyPayment(BigDecimal.ZERO)
                .processedBy("System")
                .rejectionReason("Loan approval failed: " + message)
                .sagaId(sagaId)
                .build();
        loanApprovalRepository.save(approval);

        publishLoanProcessingEvent(approval, LoanProcessingEvent.EventType.LOAN_PROCESSING_FAILED);
        log.info("Handled loan approval failure for applicationId: {}", applicationId);
    }


    private void publishLoanProcessingEvent(LoanApproval approval, LoanProcessingEvent.EventType eventType) {
        LoanProcessingEvent processingEvent = LoanProcessingEvent.builder()
                .applicationId(approval.getApplicationId())
                .approvalStatus(approval.getApprovalStatus().name())
                .approvedAmount(approval.getApprovedAmount())
                .interestRate(approval.getInterestRate())
                .loanTermMonths(approval.getLoanTermMonths())
                .monthlyPayment(approval.getMonthlyPayment())
                .processedBy(approval.getProcessedBy())
                .sagaId(approval.getSagaId())
                .eventType(eventType.name())
                .build();

        kafkaTemplate.send("loan-processing-events", processingEvent);
        log.info("Published LoanProcessingEvent for applicationId: {}", approval.getApplicationId());
    }

}
