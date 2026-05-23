package com.fintech.loan.dto.request;

import com.fintech.loan.domain.enums.PaymentMethod;
import com.fintech.loan.domain.enums.PaymentSource;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

@Data
public class RecordPaymentRequest {

    @NotNull
    @DecimalMin("1.00")
    private BigDecimal amount;

    @NotNull
    private PaymentMethod paymentMethod;

    @NotNull
    private PaymentSource source;

    @Size(max = 128)
    private String paymentReference;

    @NotNull
    private Instant receivedAt;

    // Client-generated UUID to prevent double recording
    @NotBlank
    @Size(max = 128)
    private String idempotencyKey;
}
