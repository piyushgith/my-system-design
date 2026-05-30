package com.homeloan.property.service;


import com.homeloan.creditcheck.events.PropertyValuationEvent;
import com.homeloan.property.entity.PropertyValuation;
import com.homeloan.property.events.CreditCheckEvent;
import com.homeloan.property.repository.PropertyValuationRepository;
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
public class PropertyValuationService {

    @Autowired
    private PropertyValuationRepository propertyValuationRepository;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    private final Random random = new Random();

    @KafkaListener(topics = "credit-check-events")
    public void handlePropertyValuationRequest(CreditCheckEvent event) {
        log.info("Received property valuation request: {}", event);

        if ("CREDIT_CHECK_COMPLETED".equals(event.getEventType()) && "APPROVED".equals(event.getCreditStatus())) {
            // Perform property valuation logic here
            performPropertyValuation(event);
        } else if ("CREDIT_CHECK_COMPLETED".equals(event.getEventType()) && "REJECTED".equals(event.getCreditStatus())) {
            //No valuation needed for rejected credit checks
            log.info("Credit check rejected for applicationId: {}. No property valuation performed.", event.getApplicationId());
        }
    }

    @Transactional
    private void performPropertyValuation(CreditCheckEvent event) {
        try {
            Thread.sleep(1000L + random.nextInt(4000)); // Simulate time-consuming valuation process

            PropertyValuation valuation = generateMockValuation(event);
            valuation = propertyValuationRepository.save(valuation);

            // Publish PropertyValuationEvent
            handlePropertyValuationEvent(valuation, PropertyValuationEvent.EventType.PROPERTY_VALUATION_COMPLETED);

            log.info("Property valuation event handled for applicationId: {} Value: {} Status: {}", valuation.getApplicationId(), valuation.getEstimatedValue(), valuation.getValuationStatus());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            handlePropertyValuationFailure(event, "Valuation process interrupted.");
        } catch (Exception e) {
            handlePropertyValuationFailure(event, e.getMessage());
        }
    }

    // Mock valuation generation logic
    private PropertyValuation generateMockValuation(CreditCheckEvent event) {
        BigDecimal baseValue = BigDecimal.valueOf(100000 + random.nextInt(900000));
        baseValue = baseValue.setScale(0, BigDecimal.ROUND_HALF_UP);

        PropertyValuation.ValuationStatus status;
        String remarks;
        String valuerName = "AutoValuer";
        //Random approval chance(85%)
        if (random.nextDouble() < 0.85) {
            status = PropertyValuation.ValuationStatus.APPROVED;
            remarks = "Property valuation successful.";
        } else {
            status = PropertyValuation.ValuationStatus.REJECTED;
            remarks = "Property valuation failed due to structural issue or market value below expectations.";
            //In case of rejection, set estimated value to zero
            baseValue = BigDecimal.ZERO;
        }

        //@formatter:off
        PropertyValuation valuation = PropertyValuation.builder()
                .applicationId(event.getApplicationId())
                .propertyAddress("Mock Property Address"+ event.getApplicationId())
                .valuationStatus(status)
                .valuatorName(valuerName)
                .estimatedValue(baseValue)
                .remarks("Mock valuation based on credit approval.")
                .loanToValueRatio(status == PropertyValuation.ValuationStatus.APPROVED
                        ? BigDecimal.valueOf(0.8).setScale(2, BigDecimal.ROUND_HALF_UP)
                        : BigDecimal.ZERO)
                .sagaId(event.getSagaId())
                .build();
        //@formatter:on
        return valuation;
    }


    private void handlePropertyValuationFailure(CreditCheckEvent event, String message) {
        log.error("Property valuation failed for applicationId: {}. Reason: {}", event.getApplicationId(), message);
        //@Formatter:off
        PropertyValuation rejection = PropertyValuation.builder()
                .applicationId(event.getApplicationId())
                .propertyAddress("Failed Valuation")
                .valuationStatus(PropertyValuation.ValuationStatus.REJECTED)
                .valuatorName("System")
                .estimatedValue(BigDecimal.ZERO)
                .remarks("Mock valuation based on credit approval.")
                .loanToValueRatio(BigDecimal.ZERO)
                .sagaId(event.getSagaId())
                .build();
        //@Formatter:on

        propertyValuationRepository.save(rejection);
        handlePropertyValuationEvent(rejection, PropertyValuationEvent.EventType.PROPERTY_VALUATION_FAILED);
    }


    private void handlePropertyValuationEvent(PropertyValuation valuation, PropertyValuationEvent.EventType eventType) {
        //@Formatter:off
        PropertyValuationEvent valuationEvent = PropertyValuationEvent.builder()
                .applicationId(valuation.getApplicationId())
                .propertyAddress(valuation.getPropertyAddress())
                .estimatedValue(valuation.getEstimatedValue())
                .valuationStatus(valuation.getValuationStatus().toString())
                .valuationDate(valuation.getValuationDate())
                .eventType(eventType.toString())
                .sagaId(valuation.getSagaId()).build();
        kafkaTemplate.send("property-valuation-events", valuationEvent);
        log.info("Published PropertyValuationEvent: {}", valuationEvent);
        //@Formatter:on
    }

}
