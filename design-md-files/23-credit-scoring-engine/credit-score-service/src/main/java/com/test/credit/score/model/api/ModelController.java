package com.test.credit.score.model.api;

import com.test.credit.score.model.application.ModelRegistryService;
import com.test.credit.score.model.domain.ModelRegistration;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/models")
@RequiredArgsConstructor
public class ModelController {

    private final ModelRegistryService modelRegistryService;

    @GetMapping
    public ResponseEntity<Map<String, Object>> listModels() {
        List<ModelRegistration> models = modelRegistryService.listAll();
        List<Map<String, Object>> items = models.stream().map(m -> {
            Map<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("modelVersion", m.getModelVersion());
            entry.put("modelType", m.getModelType());
            entry.put("role", m.getRole());
            entry.put("challengerTrafficPct", m.getChallengerTrafficPct());
            entry.put("productTypes", m.getProductTypes());
            entry.put("featureOrder", m.featureOrderList());
            entry.put("deployedAt", m.getDeployedAt());
            entry.put("notes", m.getNotes() != null ? m.getNotes() : "");
            return entry;
        }).toList();
        return ResponseEntity.ok(Map.of("models", items));
    }

    /** Dev: force reload of in-memory model cache (simulates hot-reload). */
    @PostMapping("/refresh")
    public ResponseEntity<Void> refresh() {
        modelRegistryService.refresh();
        return ResponseEntity.noContent().build();
    }
}
