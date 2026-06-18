package com.test.ride.sharing.service.web;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.cors")
public class CorsProperties {
    private String allowedOrigins = "http://localhost:5173";
}
