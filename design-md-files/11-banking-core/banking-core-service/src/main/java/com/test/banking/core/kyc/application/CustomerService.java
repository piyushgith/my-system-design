package com.test.banking.core.kyc.application;

import com.test.banking.core.shared.security.AccountAccessValidator;
import com.test.banking.core.kyc.api.dto.CreateCustomerRequest;
import com.test.banking.core.kyc.api.dto.CustomerResponse;
import com.test.banking.core.kyc.infrastructure.CustomerEntity;
import com.test.banking.core.kyc.infrastructure.CustomerRepository;
import com.test.banking.core.kyc.infrastructure.CifSequenceRepository;
import com.test.banking.core.kyc.infrastructure.KycRecordEntity;
import com.test.banking.core.kyc.infrastructure.KycRecordRepository;
import com.test.banking.core.shared.audit.AuditService;
import com.test.banking.core.shared.exception.ConflictException;
import com.test.banking.core.shared.exception.NotFoundException;
import com.test.banking.core.shared.util.PanHasher;
import com.test.banking.core.shared.validation.PanValidator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final KycRecordRepository kycRecordRepository;
    private final CifSequenceRepository cifSequenceRepository;
    private final StubKycService stubKycService;
    private final AuditService auditService;
    private final AccountAccessValidator accountAccessValidator;

    public CustomerService(CustomerRepository customerRepository, KycRecordRepository kycRecordRepository,
                           CifSequenceRepository cifSequenceRepository, StubKycService stubKycService,
                           AuditService auditService, AccountAccessValidator accountAccessValidator) {
        this.customerRepository = customerRepository;
        this.kycRecordRepository = kycRecordRepository;
        this.cifSequenceRepository = cifSequenceRepository;
        this.stubKycService = stubKycService;
        this.auditService = auditService;
        this.accountAccessValidator = accountAccessValidator;
    }

    @Transactional
    public CustomerResponse createCustomer(CreateCustomerRequest request) {
        String pan = PanValidator.normalizeAndValidate(request.pan());
        String panHash = PanHasher.hash(pan);
        if (customerRepository.existsByPanHash(panHash)) {
            throw new ConflictException("DUPLICATE_PAN", "Customer with this PAN already exists");
        }

        long seq = cifSequenceRepository.nextCifSequence();
        String cifId = "CIF-" + seq;
        Instant now = Instant.now();

        CustomerEntity customer = new CustomerEntity();
        customer.setCifId(cifId);
        customer.setFirstName(request.firstName());
        customer.setLastName(request.lastName());
        customer.setDateOfBirth(request.dateOfBirth());
        customer.setGender(request.gender());
        customer.setPanHash(panHash);
        customer.setAadhaarToken(request.aadhaarToken());
        customer.setCustomerType("INDIVIDUAL");
        customer.setStatus("ACTIVE");
        customer.setRiskRating("LOW");
        customer.setPepFlag(false);
        customer.setCreatedAt(now);
        customer.setUpdatedAt(now);
        customerRepository.save(customer);

        KycRecordEntity kyc = stubKycService.initiateAndVerify(cifId);
        CustomerResponse response = toResponse(customer, kyc);
        auditService.record("CUSTOMER_CREATED", "CUSTOMER", cifId, response);
        return response;
    }

    @Transactional(readOnly = true)
    public CustomerResponse getCustomer(String cifId) {
        accountAccessValidator.assertCanAccessCustomer(cifId);
        CustomerEntity customer = customerRepository.findByCifId(cifId)
                .orElseThrow(() -> new NotFoundException("Customer not found: " + cifId));
        KycRecordEntity kyc = kycRecordRepository.findTopByCifIdOrderByCreatedAtDesc(cifId).orElse(null);
        return toResponse(customer, kyc);
    }

    private CustomerResponse toResponse(CustomerEntity customer, KycRecordEntity kyc) {
        return new CustomerResponse(
                customer.getCifId(),
                customer.getFirstName(),
                customer.getLastName(),
                customer.getDateOfBirth(),
                customer.getStatus(),
                kyc != null ? kyc.getStatus() : "PENDING",
                kyc != null ? kyc.getVerifiedAt() : null
        );
    }
}
