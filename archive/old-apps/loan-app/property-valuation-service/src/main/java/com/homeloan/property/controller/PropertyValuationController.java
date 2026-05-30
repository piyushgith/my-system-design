package com.homeloan.property.controller;


import com.homeloan.property.entity.PropertyValuation;
import com.homeloan.property.repository.PropertyValuationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

@RestController
@Slf4j
@RequestMapping("/api/property")
public class PropertyValuationController {

    @Autowired
    private PropertyValuationRepository propertyValuationRepository;

    @GetMapping("/valuations/{applicationId}")
    public ResponseEntity<PropertyValuation> getPropertyValuation(@PathVariable Long applicationId) {
        log.info("Fetching property valuation for applicationId: {}", applicationId);
        Optional<PropertyValuation> valuation = propertyValuationRepository.findByApplicationId(applicationId);
        if (valuation != null) {
            return ResponseEntity.ok(valuation.get());
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/valuations/history/{applicationId}")
    public ResponseEntity<List<PropertyValuation>> getPropertyValuationHistory(@PathVariable Long applicationId) {
        log.info("Fetching property valuation history for applicationId: {}", applicationId);
        List<PropertyValuation> valuations = propertyValuationRepository.findByApplicationIdOrderByValuationDateDesc(applicationId);
        return ResponseEntity.ok(valuations);
    }


    @GetMapping("/valuations")
    public ResponseEntity<List<PropertyValuation>> getAllPropertyValuations() {
        log.info("Fetching all property valuations");
        List<PropertyValuation> valuations = propertyValuationRepository.findAll();
        return ResponseEntity.ok(valuations);
    }

    @GetMapping("/health")
    public ResponseEntity<String> healthCheck() {
        log.info("Property Valuation Service is healthy");
        return ResponseEntity.ok("Property Valuation Service is healthy");
    }

}
