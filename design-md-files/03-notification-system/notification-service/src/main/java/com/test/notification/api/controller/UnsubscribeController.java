package com.test.notification.api.controller;

import com.test.notification.service.PreferenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/unsubscribe")
@RequiredArgsConstructor
public class UnsubscribeController {

    private final PreferenceService preferenceService;

    @GetMapping
    public ResponseEntity<Map<String, Object>> unsubscribe(@RequestParam String token) {
        return preferenceService.processUnsubscribeToken(token)
                .<ResponseEntity<Map<String, Object>>>map(pref -> ResponseEntity.ok(Map.of(
                        "message", "You have been unsubscribed.",
                        "channel", pref.getChannel(),
                        "category", pref.getCategory()
                )))
                .orElse(ResponseEntity.badRequest().body(Map.of(
                        "message", "Invalid or expired unsubscribe token."
                )));
    }
}
