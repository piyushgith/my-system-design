package com.homeloan.creditcheck.dto;


import com.homeloan.application.dto.ApplicationStatus;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoanApplicationDto implements Serializable {

    private Long id;

    @NotNull(message = "Name is required")
    private String applicantName;

    @NotNull(message = "Phone number is required")
    private String applicantPhone;

    @NotNull(message = "Email is required")
    private String applicantEmail;

    @NotNull(message = "Loan Amount is required")
    private BigDecimal loanAmount;

    @NotNull(message = "ApplicationStatus is required")
    private ApplicationStatus applicationStatus;

    @NotNull(message = "propertyAddress is required")
    private String propertyAddress;

    private LocalDateTime createAt;

    private LocalDateTime updatedAt;


}

