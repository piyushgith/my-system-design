package com.java.leave.management.system.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

// ============= JWT Properties =============
@ConfigurationProperties(prefix = "jwt")
@Data
@Component
public class JwtProperties {
    private String secretKey = "rzxlszyykpbgqcflzxsqcysyhljt";

    // validity in milliseconds
    private long validityInMs = 3600000; // 1h
}