package com.test.file.storage.service.service;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@Configuration
@EnableAsync
class AsyncConfig {

    @Bean(name = "taskExecutor")
    Executor taskExecutor() {
        // Virtual threads: no pool sizing, cheap blocking IO, Java 21+
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
