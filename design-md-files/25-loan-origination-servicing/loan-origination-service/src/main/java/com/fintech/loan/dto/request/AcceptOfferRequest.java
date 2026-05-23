package com.fintech.loan.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AcceptOfferRequest {

    @NotBlank
    @Size(max = 18)
    private String disbursementAccountNumber;

    @NotBlank
    @Pattern(regexp = "[A-Z]{4}0[A-Z0-9]{6}", message = "Invalid IFSC code")
    private String disbursementIfsc;

    @NotNull
    private Boolean nachConsent;

    @Size(max = 128)
    private String esignReference;
}
