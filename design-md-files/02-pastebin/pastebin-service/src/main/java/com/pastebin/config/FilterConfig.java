package com.pastebin.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FilterConfig {

    @Bean
    FilterRegistrationBean<CorrelationIdFilter> correlationIdFilterRegistration(CorrelationIdFilter filter) {
        FilterRegistrationBean<CorrelationIdFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(filter);
        registration.setOrder(1);
        return registration;
    }

    @Bean
    FilterRegistrationBean<RedisRateLimitFilter> rateLimitFilterRegistration(RedisRateLimitFilter filter) {
        FilterRegistrationBean<RedisRateLimitFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(filter);
        registration.setOrder(2);
        registration.addUrlPatterns("/api/v1/pastes");
        return registration;
    }
}
