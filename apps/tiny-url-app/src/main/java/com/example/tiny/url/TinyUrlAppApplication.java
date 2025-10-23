package com.example.tiny.url;

import com.example.tiny.url.config.RateLimitingFilter;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;

@SpringBootApplication(exclude = {RedisAutoConfiguration.class})
public class TinyUrlAppApplication {

	public static void main(String[] args) {
		SpringApplication.run(TinyUrlAppApplication.class, args);
	}


    //@Bean
    public FilterRegistrationBean<RateLimitingFilter> rateLimitingFilter() {
        FilterRegistrationBean<RateLimitingFilter> registrationBean = new FilterRegistrationBean<>();
        registrationBean.setFilter(new RateLimitingFilter());
        registrationBean.addUrlPatterns("/api/*"); // Register filter for API endpoints
        return registrationBean;
    }
}
