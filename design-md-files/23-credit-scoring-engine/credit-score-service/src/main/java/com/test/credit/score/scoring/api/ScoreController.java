package com.test.credit.score.scoring.api;

import com.test.credit.score.scoring.application.ScoringService;
import com.test.credit.score.scoring.domain.ScoreRecordRepository;
import com.test.credit.score.scoring.domain.ProductType;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/scores")
@RequiredArgsConstructor
public class ScoreController {

    private final ScoringService scoringService;
    private final ScoreRecordRepository scoreRecordRepository;

    /** Compute or return a cached credit score. Idempotent on requestId. */
    @PostMapping
    public ResponseEntity<ScoreResponse> computeScore(@Valid @RequestBody ScoreRequest request) {
        return ResponseEntity.ok(scoringService.computeScore(request));
    }

    /** Fetch full score detail (with feature snapshot) by requestId — used for audit/compliance. */
    @GetMapping("/{requestId}")
    public ResponseEntity<ScoreResponse> getScore(@PathVariable String requestId) {
        return ResponseEntity.ok(scoringService.getById(requestId));
    }

    /** Paginated score history for a user. */
    @GetMapping("/history")
    public ResponseEntity<Map<String, Object>> history(
            @RequestParam String userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) ProductType productType) {

        Pageable pageable = PageRequest.of(page, size);
        var records = productType != null
                ? scoreRecordRepository.findByUserIdOrderByComputedAtDesc(userId, pageable)
                : scoreRecordRepository.findByUserIdOrderByComputedAtDesc(userId, pageable);

        Page<ScoreHistoryItem> items = records.map(r -> new ScoreHistoryItem(
                r.getRequestId(), r.getScore(), r.getScoreBand(),
                r.getModelVersion(), r.getProductType(), r.getSource(),
                r.getModelRole(), r.getComputedAt()));

        return ResponseEntity.ok(Map.of(
                "userId", userId,
                "page", items.getNumber(),
                "totalPages", items.getTotalPages(),
                "totalItems", items.getTotalElements(),
                "items", items.getContent()
        ));
    }
}
