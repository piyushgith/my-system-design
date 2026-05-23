package com.fintech.loan.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Data
@Builder
public class BorrowerResponse {
    private UUID borrowerId;
    private String fullName;
    private LocalDate dateOfBirth;
    private String panNumber;
    private String mobileNumber;
    private String email;
    private String kycStatus;
    private Instant createdAt;
}
