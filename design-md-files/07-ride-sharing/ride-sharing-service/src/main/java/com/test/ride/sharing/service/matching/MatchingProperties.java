package com.test.ride.sharing.service.matching;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.matching")
public class MatchingProperties {
    private String backend = "nearest";
    private double searchRadiusKm = 5;
    private double radiusExpansionKm = 5;
    private int maxRounds = 3;
    private int offerTimeoutSeconds = 15;
    private int totalTimeoutSeconds = 180;
}
