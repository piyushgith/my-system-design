package com.test.banking.core.account.api;

import com.test.banking.core.account.api.dto.LienResponse;
import com.test.banking.core.account.api.dto.PlaceLienRequest;
import com.test.banking.core.account.application.LienService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/accounts/{accountId}/liens")
@SecurityRequirement(name = "basicAuth")
public class LienController {

    private final LienService lienService;

    public LienController(LienService lienService) {
        this.lienService = lienService;
    }

    @GetMapping
    @PreAuthorize("hasRole('TELLER')")
    public List<LienResponse> listActiveLiens(@PathVariable String accountId) {
        return lienService.listActiveLiens(accountId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('TELLER')")
    public LienResponse placeLien(@PathVariable String accountId,
                                  @Valid @RequestBody PlaceLienRequest request) {
        return lienService.placeLien(accountId, request);
    }

    @PostMapping("/{lienId}/release")
    @PreAuthorize("hasRole('TELLER')")
    public LienResponse releaseLien(@PathVariable String accountId, @PathVariable UUID lienId) {
        return lienService.releaseLien(accountId, lienId);
    }
}
