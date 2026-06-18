package com.test.ride.sharing.service.location;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.location")
public class LocationProperties {
    private String backend = "mock-redis";
}
