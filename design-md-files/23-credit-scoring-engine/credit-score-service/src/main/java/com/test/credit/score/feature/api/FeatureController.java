package com.test.credit.score.feature.api;

import com.test.credit.score.feature.application.FeatureStoreService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/features")
@RequiredArgsConstructor
public class FeatureController {

    private final FeatureStoreService featureStoreService;

    /** Admin: inspect current feature values for a user. */
    @GetMapping("/{userId}")
    public ResponseEntity<Map<String, String>> getFeatures(@PathVariable String userId) {
        Map<String, String> features = featureStoreService.getRawFeatures(userId);
        return ResponseEntity.ok(features);
    }

    /** Dev/test: manually seed feature values for a user (simulates bureau refresh). */
    @PostMapping("/{userId}/seed")
    public ResponseEntity<Void> seedFeatures(
            @PathVariable String userId,
            @Valid @RequestBody FeatureSeedRequest request) {
        featureStoreService.seedFeatures(userId, request.features());
        return ResponseEntity.noContent().build();
    }
}
