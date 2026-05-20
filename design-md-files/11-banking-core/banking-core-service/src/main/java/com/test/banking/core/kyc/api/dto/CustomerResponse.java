package com.test.banking.core.kyc.api.dto;

import java.time.Instant;
import java.time.LocalDate;

public record CustomerResponse(
        String cifId,
        String firstName,
        String lastName,
        LocalDate dateOfBirth,
        String customerStatus,
        String kycStatus,
        Instant kycVerifiedAt) {
}
