package com.test.banking.core.kyc.application;

import com.test.banking.core.kyc.api.KycPublicApi;
import com.test.banking.core.kyc.infrastructure.CustomerRepository;
import com.test.banking.core.kyc.infrastructure.KycRecordRepository;
import com.test.banking.core.shared.exception.NotFoundException;
import org.springframework.stereotype.Service;

@Service
public class KycPublicApiImpl implements KycPublicApi {

    private final CustomerRepository customerRepository;
    private final KycRecordRepository kycRecordRepository;

    public KycPublicApiImpl(CustomerRepository customerRepository, KycRecordRepository kycRecordRepository) {
        this.customerRepository = customerRepository;
        this.kycRecordRepository = kycRecordRepository;
    }

    @Override
    public boolean isKycVerified(String cifId) {
        return kycRecordRepository.findTopByCifIdOrderByCreatedAtDesc(cifId)
                .map(k -> "VERIFIED".equals(k.getStatus()))
                .orElse(false);
    }

    @Override
    public boolean customerExists(String cifId) {
        return customerRepository.existsById(cifId);
    }

    @Override
    public String getCustomerStatus(String cifId) {
        return customerRepository.findByCifId(cifId)
                .orElseThrow(() -> new NotFoundException("Customer not found: " + cifId))
                .getStatus();
    }
}
