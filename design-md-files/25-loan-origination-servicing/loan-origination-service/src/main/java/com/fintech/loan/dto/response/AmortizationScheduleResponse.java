package com.fintech.loan.dto.response;

import com.fintech.loan.domain.enums.InstallmentStatus;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class AmortizationScheduleResponse {
    private UUID loanAccountId;
    private String loanAccountNumber;
    private BigDecimal emiAmount;
    private Integer totalInstallments;
    private List<InstallmentEntry> schedule;

    @Data
    @Builder
    public static class InstallmentEntry {
        private Integer installmentNumber;
        private LocalDate dueDate;
        private BigDecimal openingPrincipal;
        private BigDecimal emiAmount;
        private BigDecimal principalComponent;
        private BigDecimal interestComponent;
        private BigDecimal closingPrincipal;
        private InstallmentStatus status;
    }
}
