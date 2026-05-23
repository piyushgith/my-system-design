package com.fintech.loan.service;

import com.fintech.loan.domain.entity.AmortizationEntry;
import com.fintech.loan.domain.enums.InstallmentStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class AmortizationService {

    private static final MathContext MC = new MathContext(10, RoundingMode.HALF_UP);
    private static final int SCALE = 2;

    /**
     * Flat-rate EMI formula: P * r * (1+r)^n / ((1+r)^n - 1)
     * where r = monthly interest rate, n = tenure months
     */
    public BigDecimal calculateEmi(BigDecimal principal, BigDecimal annualRate, int tenureMonths) {
        BigDecimal monthlyRate = annualRate.divide(BigDecimal.valueOf(12), MC);
        if (monthlyRate.compareTo(BigDecimal.ZERO) == 0) {
            return principal.divide(BigDecimal.valueOf(tenureMonths), SCALE, RoundingMode.HALF_UP);
        }
        BigDecimal onePlusR = BigDecimal.ONE.add(monthlyRate, MC);
        BigDecimal onePlusRPowN = onePlusR.pow(tenureMonths, MC);
        BigDecimal numerator = principal.multiply(monthlyRate, MC).multiply(onePlusRPowN, MC);
        BigDecimal denominator = onePlusRPowN.subtract(BigDecimal.ONE, MC);
        return numerator.divide(denominator, SCALE, RoundingMode.HALF_UP);
    }

    public List<AmortizationEntry> generateSchedule(UUID loanAccountId,
                                                     BigDecimal principal,
                                                     BigDecimal annualRate,
                                                     int tenureMonths,
                                                     LocalDate firstDueDate) {
        BigDecimal emi = calculateEmi(principal, annualRate, tenureMonths);
        BigDecimal monthlyRate = annualRate.divide(BigDecimal.valueOf(12), MC);

        List<AmortizationEntry> schedule = new ArrayList<>();
        BigDecimal outstanding = principal;

        for (int i = 1; i <= tenureMonths; i++) {
            BigDecimal interestComponent = outstanding.multiply(monthlyRate, MC)
                    .setScale(SCALE, RoundingMode.HALF_UP);
            BigDecimal principalComponent;
            BigDecimal emiThisMonth;

            if (i == tenureMonths) {
                // Last installment: clear remaining balance to avoid rounding drift
                principalComponent = outstanding;
                emiThisMonth = principalComponent.add(interestComponent);
            } else {
                principalComponent = emi.subtract(interestComponent);
                emiThisMonth = emi;
            }

            BigDecimal closingPrincipal = outstanding.subtract(principalComponent)
                    .max(BigDecimal.ZERO);

            schedule.add(AmortizationEntry.builder()
                    .loanAccountId(loanAccountId)
                    .installmentNumber(i)
                    .dueDate(firstDueDate.plusMonths(i - 1L))
                    .openingPrincipal(outstanding)
                    .emiAmount(emiThisMonth)
                    .principalComponent(principalComponent)
                    .interestComponent(interestComponent)
                    .closingPrincipal(closingPrincipal)
                    .status(InstallmentStatus.SCHEDULED)
                    .build());

            outstanding = closingPrincipal;
        }
        return schedule;
    }
}
