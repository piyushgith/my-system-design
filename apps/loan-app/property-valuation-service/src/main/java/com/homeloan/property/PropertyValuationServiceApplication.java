package com.homeloan.property;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.kafka.annotation.EnableKafka;

@EnableKafka
@EnableDiscoveryClient
@SpringBootApplication
public class PropertyValuationServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(PropertyValuationServiceApplication.class, args);
	}

}
