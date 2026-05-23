package com.fintech.loan.dto.request;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.time.LocalDate;

@Data
public class CreateBorrowerRequest {

    @NotBlank
    @Size(max = 256)
    private String fullName;

    @NotNull
    @Past
    private LocalDate dateOfBirth;

    @Pattern(regexp = "[A-Z]{5}[0-9]{4}[A-Z]", message = "Invalid PAN format")
    private String panNumber;

    @Size(max = 15)
    private String mobileNumber;

    @Email
    @Size(max = 256)
    private String email;
}
