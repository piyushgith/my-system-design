package com.homeloan.gateway.controller;


import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/fallback")
public class FallbackController {

    @GetMapping("/saga-orchestrator")
    @PostMapping("/saga-orchestrator")
    public ResponseEntity<Map<String, Object>> _Fallback() {
        return createFallbackResponse("");
    }

    @GetMapping("/")
    @PostMapping("/")
    public ResponseEntity<Map<String, Object>> _Fallback() {
        return createFallbackResponse("");
    }

    @GetMapping("/")
    @PostMapping("/")
    public ResponseEntity<Map<String, Object>> _Fallback() {
        return createFallbackResponse("");
    }

    @GetMapping("/")
    @PostMapping("/")
    public ResponseEntity<Map<String, Object>> _Fallback() {
        return createFallbackResponse("");
    }

    @GetMapping("/")
    @PostMapping("/")
    public ResponseEntity<Map<String, Object>> _Fallback() {
        return createFallbackResponse("");
    }

    @GetMapping("/")
    @PostMapping("/")
    public ResponseEntity<Map<String, Object>> _Fallback() {
        return createFallbackResponse("");
    }

    @GetMapping("/")
    @PostMapping("/")
    public ResponseEntity<Map<String, Object>> _Fallback() {
        return createFallbackResponse("");
    }





    private ResponseEntity<Map<String, Object>> createFallbackResponse(String message) {
        Map<String, Object> responseMap = new HashMap<>();
        responseMap.put("error", "Service Unavailable");
        responseMap.put("message", message);
        responseMap.put("timestamp", LocalDateTime.now());
        responseMap.put("status", HttpStatus.SERVICE_UNAVAILABLE);

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(responseMap);
    }


}
