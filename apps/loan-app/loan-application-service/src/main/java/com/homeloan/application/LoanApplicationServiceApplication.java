package com.homeloan.application;

import com.homeloan.application.mapper.LoanApplicationMapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class LoanApplicationServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(LoanApplicationServiceApplication.class, args);
	}

}
