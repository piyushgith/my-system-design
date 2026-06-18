package com.test.ride.sharing.service.trip;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.trip")
public class TripProperties {
    private int matchingTimeoutSeconds = 180;
    private int cancellationFeeDispatched = 30;
    private int cancellationFeeArrived = 50;
}
