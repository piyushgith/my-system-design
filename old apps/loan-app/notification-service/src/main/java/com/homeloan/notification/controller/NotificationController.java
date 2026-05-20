package com.homeloan.notification.controller;


import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/notifications")
@Slf4j
public class NotificationController {

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        log.info("Notification service status checked");
        Map<String, Object> status = new HashMap<>();
        status.put("service", "Notification Service");
        status.put("status", "UP");
        status.put("timestamp", LocalDateTime.now());
        status.put("features", new String[]{"Email Notifications", "SMS Notifications", "Push Notifications"});

        return ResponseEntity.ok(status);
    }

    @GetMapping("/health")
    public ResponseEntity<String> healthCheck() {
        log.info("Health check endpoint called");
        return ResponseEntity.ok("Notification Service is healthy");
    }

}
