package com.test.banking.core.kyc.application;

import com.test.banking.core.kyc.infrastructure.KycRecordEntity;
import com.test.banking.core.kyc.infrastructure.KycRecordRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Service
public class StubKycService {

    private final KycRecordRepository kycRecordRepository;

    public StubKycService(KycRecordRepository kycRecordRepository) {
        this.kycRecordRepository = kycRecordRepository;
    }

    public KycRecordEntity initiateAndVerify(String cifId) {
        KycRecordEntity record = new KycRecordEntity();
        record.setKycId(UUID.randomUUID());
        record.setCifId(cifId);
        record.setKycType("FULL");
        record.setStatus("VERIFIED");
        record.setVerifiedBy("STUB-CKYC");
        record.setVerifiedAt(Instant.now());
        record.setExpiryDate(LocalDate.now().plusYears(2));
        record.setCreatedAt(Instant.now());
        return kycRecordRepository.save(record);
    }
}
