package com.homeloan.creditcheck.service;

import com.homeloan.creditcheck.entity.CreditCheck;
import com.homeloan.creditcheck.entity.CreditStatus;
import com.homeloan.creditcheck.events.CreditCheckEvent;
import com.homeloan.creditcheck.events.LoanApplicationEvent;
import com.homeloan.creditcheck.repository.CreditCheckRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Random;

@Service
@Slf4j
public class CreditCheckService {

    @Autowired
    private CreditCheckRepository creditCheckRepository;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    private final Random random = new Random();

    @KafkaListener(topics = "loan-application-events")
    public void handleLoanApplicationEvent(LoanApplicationEvent event) {
        if ("LOAN_APPLICATION_CREATED".equalsIgnoreCase(event.getEventType())) {
            log.info("Processing credit check for ApplicationId: {}", event.getApplicationId());
            performCreditCheck(event.getApplicationId(), event.getSagaId());
        } else {
            log.error("event.getEventType(); {}", event.getEventType());
        }
    }

    @Transactional
    private void performCreditCheck(Long applicationId, String sagaId) {
        CreditStatus creditStatus;
        String remarks;
        try {
            Thread.sleep(2000L + random.nextInt(3000));
            //range 330-850
            int creditScore = 300 + random.nextInt(551);

            if (creditScore >= 700) {
                creditStatus = CreditStatus.APPROVED;
                remarks = "Credit score is good. Approve with Low Interest Rate.";
            } else if (creditScore >= 650) {
                creditStatus = CreditStatus.APPROVED;
                remarks = "Credit score is fair. Approve with Standard Interest Rate.";
            } else if (creditScore >= 600) {
                creditStatus = CreditStatus.APPROVED;
                remarks = "Credit score is below average. Approve with High Interest Rate.";
            } else {
                creditStatus = CreditStatus.REJECTED;
                remarks = "Credit score is poor. Reject the application.";
            }
            //save it
            CreditCheck creditCheck = CreditCheck.builder()
                    .applicationId(applicationId)
                    .creditScore(creditScore)
                    .creditStatus(creditStatus)
                    .remarks(remarks)
                    .sagaId(sagaId)
                    .build();

            creditCheckRepository.save(creditCheck);
            //publish event
            publishCreditCheckCompletedEvent(creditCheck,applicationId,sagaId,"CREDIT_CHECK_COMPLETED");
            log.info("Credit check completed for ApplicationId: {} with Credit Score: {}", applicationId, creditScore);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            handleCreditCheckFailure(applicationId, sagaId, "Credit check process was interrupted.");
        } catch (Exception e) {
            log.error("Error during credit check for ApplicationId: {}", applicationId, e.getMessage());
            handleCreditCheckFailure(applicationId, sagaId, e.getMessage());
        }
    }

    private void handleCreditCheckFailure(Long applicationId, String sagaId, String errorMessage) {
        CreditCheck creditCheck = CreditCheck.builder()
                .applicationId(applicationId)
                .creditScore(0)
                .creditStatus(CreditStatus.REJECTED)
                .remarks("Credit check failed: " + errorMessage)
                .sagaId(sagaId)
                .build();
        creditCheckRepository.save(creditCheck);
        publishCreditCheckCompletedEvent(creditCheck,applicationId,sagaId,"CREDIT_CHECK_FAILED");
    }

    public void publishCreditCheckCompletedEvent(CreditCheck creditCheck,Long applicationId,String sagaId,String creditStatus) {
        CreditCheckEvent event = CreditCheckEvent.builder()
                .applicationId(applicationId)
                .creditScore(creditCheck.getCreditScore())
                .creditStatus(creditCheck.getCreditStatus().name())
                .remarks(creditCheck.getRemarks())
                .sagaId(sagaId)
                .eventType(creditStatus)
                .build();
        kafkaTemplate.send("credit-check-events", event);
        log.info("Published CreditCheckEvent for ApplicationId: {} with Status: {}", applicationId, creditStatus);
    }

}
