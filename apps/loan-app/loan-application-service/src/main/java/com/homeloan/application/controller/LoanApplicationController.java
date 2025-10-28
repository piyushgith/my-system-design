package com.homeloan.application.controller;


import com.homeloan.application.dto.ApplicationStatus;
import com.homeloan.application.entity.LoanApplication;
import com.homeloan.application.service.LoanApplicationService;
import com.homeloan.creditcheck.dto.LoanApplicationDto;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/loans")
public class LoanApplicationController {

    @Autowired
    private LoanApplicationService loanApplicationService;

    @PostMapping("/applications")
    public ResponseEntity<LoanApplicationDto> createLoanApplication(@Valid @RequestBody LoanApplicationDto loanApplicationDto) {
        // Implementation goes here
        LoanApplicationDto createdApplication = loanApplicationService.createLoanApplication(loanApplicationDto);
        return ResponseEntity.ok(createdApplication);
    }

    @GetMapping("/applications/{id}")
    public ResponseEntity<LoanApplicationDto> getLoanApplicationById(@PathVariable Long id) {
        // Implementation goes here
        return loanApplicationService.getLoanApplicationById(id)
                .map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/applications/email/{email}")
    public ResponseEntity<List<LoanApplicationDto>> getLoanApplicationByEmail(@PathVariable String email) {
        // Implementation goes here
        List<LoanApplicationDto> applicationDtoList = loanApplicationService.getLoanApplicationByEmail(email);
        return ResponseEntity.ok(applicationDtoList);
    }

    @GetMapping("/applications/{id}/status")
    public ResponseEntity<String> getLoanApplicationStatus(@PathVariable Long id) {
        // Implementation goes here
        Optional<LoanApplicationDto> applicationDto = loanApplicationService.getLoanApplicationById(id);
        return applicationDto
                .map(app -> ResponseEntity.ok(app.getApplicationStatus().toString()))
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/applications/{id}/status")
    public ResponseEntity<LoanApplicationDto> updateLoanApplicationStatus(@PathVariable Long id, @RequestParam ApplicationStatus newStatus) {
        // Implementation goes here
        LoanApplicationDto updatedApplicationDto = loanApplicationService.updateApplicationStatus(id, newStatus);
        return ResponseEntity.ok(updatedApplicationDto);
    }

}
