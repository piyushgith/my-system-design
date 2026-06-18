package com.test.ride.sharing.service.event;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.events")
public class EventProperties {
    private String backend = "mock-kafka";
}
