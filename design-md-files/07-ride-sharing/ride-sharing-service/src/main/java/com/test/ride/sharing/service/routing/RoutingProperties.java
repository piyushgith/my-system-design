package com.test.ride.sharing.service.routing;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.routing")
public class RoutingProperties {
    private String backend = "mock";
    private Mock mock = new Mock();
    private Osrm osrm = new Osrm();

    @Getter
    @Setter
    public static class Mock {
        private double avgSpeedKmh = 30;
    }

    @Getter
    @Setter
    public static class Osrm {
        private String baseUrl = "http://localhost:5000";
    }
}
