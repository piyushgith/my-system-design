package com.test.banking.core.ledger.api;

import com.test.banking.core.ledger.api.dto.StatementRequest;
import com.test.banking.core.ledger.api.dto.StatementResponse;
import com.test.banking.core.ledger.application.StatementService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;

@RestController
@RequestMapping("/api/v1/accounts/{accountId}/statements")
@SecurityRequirement(name = "basicAuth")
public class StatementController {

    private final StatementService statementService;

    public StatementController(StatementService statementService) {
        this.statementService = statementService;
    }

    @PostMapping("/request")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasAnyRole('TELLER', 'CUSTOMER')")
    public StatementResponse requestStatement(@PathVariable String accountId,
                                              @Valid @RequestBody StatementRequest request) {
        return statementService.generateStatement(accountId, request);
    }
}
