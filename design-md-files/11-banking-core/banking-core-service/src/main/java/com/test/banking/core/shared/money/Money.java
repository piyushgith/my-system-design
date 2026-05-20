package com.test.banking.core.shared.money;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record Money(long paise) {

    public static Money ofRupees(BigDecimal rupees) {
        if (rupees == null) {
            throw new IllegalArgumentException("Amount is required");
        }
        return new Money(rupees.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact());
    }

    public static Money ofPaise(long paise) {
        if (paise < 0) {
            throw new IllegalArgumentException("Amount cannot be negative");
        }
        return new Money(paise);
    }

    public BigDecimal toRupees() {
        return BigDecimal.valueOf(paise, 2);
    }

    public Money add(Money other) {
        return new Money(this.paise + other.paise);
    }

    public Money subtract(Money other) {
        return new Money(this.paise - other.paise);
    }
}
