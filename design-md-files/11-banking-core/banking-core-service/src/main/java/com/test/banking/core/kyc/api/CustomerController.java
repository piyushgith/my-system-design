package com.test.banking.core.kyc.api;

import com.test.banking.core.kyc.api.dto.CreateCustomerRequest;
import com.test.banking.core.kyc.api.dto.CustomerResponse;
import com.test.banking.core.kyc.application.CustomerService;
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
import io.swagger.v3.oas.annotations.security.SecurityRequirement;

@RestController
@RequestMapping("/api/v1/customers")
@SecurityRequirement(name = "basicAuth")
public class CustomerController {

    private final CustomerService customerService;

    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('TELLER')")
    public CustomerResponse createCustomer(@Valid @RequestBody CreateCustomerRequest request) {
        return customerService.createCustomer(request);
    }

    @GetMapping("/{cifId}")
    @PreAuthorize("hasAnyRole('TELLER', 'CUSTOMER')")
    public CustomerResponse getCustomer(@PathVariable String cifId) {
        return customerService.getCustomer(cifId);
    }
}
