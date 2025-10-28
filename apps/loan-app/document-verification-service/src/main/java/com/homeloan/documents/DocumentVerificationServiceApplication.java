package com.homeloan.documents;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafka;

@SpringBootApplication
@EnableKafka
public class DocumentVerificationServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(DocumentVerificationServiceApplication.class, args);
	}

}
