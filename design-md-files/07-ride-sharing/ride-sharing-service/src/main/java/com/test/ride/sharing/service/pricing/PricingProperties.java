package com.test.ride.sharing.service.pricing;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.pricing")
public class PricingProperties {
    private int baseFare = 40;
    private int perKmRate = 10;
    private int perMinRate = 1;
    private int platformFee = 10;
    private int minimumFare = 50;
    private int quoteTtlMinutes = 5;
    private BigDecimal defaultSurgeMultiplier = BigDecimal.valueOf(1.0);
}
