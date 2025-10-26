package com.homeloan.creditcheck.controller;


import com.homeloan.creditcheck.entity.CreditCheck;
import com.homeloan.creditcheck.repository.CreditCheckRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/credit")
public class CreditCheckController {

    @Autowired
    private CreditCheckRepository creditCheckRepository;

    @GetMapping("/checks/{applicationId}")
    public ResponseEntity<CreditCheck> checkCredit(@PathVariable Long applicationId) {
        Optional<CreditCheck> creditCheck = creditCheckRepository.findByApplicationId(applicationId);
        if (creditCheck.isPresent()) {
            return ResponseEntity.ok(creditCheck.get());
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/checks/history/{applicationId}")
    public ResponseEntity<List<CreditCheck>> getCreditCheckHistory(@PathVariable Long applicationId) {
        List<CreditCheck> creditChecks = creditCheckRepository.findByApplicationIdOrderByCheckDateDesc(applicationId);
        return ResponseEntity.ok(creditChecks);
    }

    @GetMapping("/checks")
    public ResponseEntity<List<CreditCheck>> getAllCreditChecks() {
        List<CreditCheck> creditChecks = creditCheckRepository.findAll();
        return ResponseEntity.ok(creditChecks);
    }

    @GetMapping("/health")
    public ResponseEntity<String> healthCheck() {
        return ResponseEntity.ok("Credit Check Service is up and running!");
    }


}
