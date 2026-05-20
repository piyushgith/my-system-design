package com.test.credit.score.scoring.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.test.credit.score.config.ScoringProperties;
import com.test.credit.score.scoring.api.ReasonCodeDto;
import com.test.credit.score.scoring.api.ScoreRequest;
import com.test.credit.score.scoring.api.ScoreResponse;
import com.test.credit.score.scoring.domain.*;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ScoringService {

    private final IdempotencyService idempotencyService;
    private final ScoreCacheService scoreCacheService;
    private final FeatureAssembler featureAssembler;
    private final ModelInferenceService modelInferenceService;
    private final ReasonCodeService reasonCodeService;
    private final ScoreRecordRepository scoreRecordRepository;
    private final ObjectMapper objectMapper;
    private final ScoringProperties props;
    private final MeterRegistry meterRegistry;

    public ScoreResponse computeScore(ScoreRequest request) {
        Timer.Sample timerSample = Timer.start(meterRegistry);

        try {
            // 1. Idempotency — same request_id returns same result
            Optional<ScoreResponse> idempotent = idempotencyService.get(request.requestId());
            if (idempotent.isPresent()) {
                log.debug("Idempotency hit for requestId={}", request.requestId());
                meterRegistry.counter("scoring.requests", "result", "idempotent").increment();
                return idempotent.get();
            }

            // 2. Score cache (skip if force_refresh)
            if (!request.forceRefresh()) {
                Optional<ScoreResponse> cached = scoreCacheService.get(request.userId(), request.productType());
                if (cached.isPresent()) {
                    log.debug("Score cache hit for userId={} product={}", request.userId(), request.productType());
                    meterRegistry.counter("scoring.requests", "result", "cache_hit").increment();
                    return cached.get();
                }
            }

            // 3. Determine champion vs challenger by stable hash of userId
            ModelRole role = determineRole(request.userId());

            // 4. Assemble feature vector from Redis (or defaults if Redis unavailable)
            FeatureVector featureVector = featureAssembler.assemble(request.userId(), request.productType());

            // 5. Model inference
            InferenceResult inference = modelInferenceService.infer(featureVector, role, request.productType());

            // 6. Reason codes (top 4 feature contributions)
            List<ReasonCodeItem> reasonCodeItems = reasonCodeService.compute(featureVector, inference);

            // 7. Persist audit record (synchronous in Phase 0; async via Kafka in MVP+)
            ScoreRecord record = persist(request, inference, featureVector, reasonCodeItems);

            // 8. Build response
            ScoreResponse response = toResponse(record, reasonCodeItems, featureVector);

            // 9. Cache result
            scoreCacheService.put(request.userId(), request.productType(), response);
            idempotencyService.put(request.requestId(), response);

            meterRegistry.counter("scoring.requests", "result", "computed").increment();
            meterRegistry.gauge("scoring.score.value", inference.score());
            log.info("Scored userId={} score={} model={} role={}", request.userId(), inference.score(), inference.modelVersion(), role);
            return response;

        } finally {
            timerSample.stop(Timer.builder("scoring.latency")
                    .description("Score computation latency")
                    .register(meterRegistry));
        }
    }

    public ScoreResponse getById(String requestId) {
        ScoreRecord record = scoreRecordRepository.findById(requestId)
                .orElseThrow(() -> new ScoreNotFoundException(requestId));
        return toResponseFromRecord(record);
    }

    private ModelRole determineRole(String userId) {
        int hash = Math.abs(userId.hashCode()) % 100;
        return hash < props.getChallengerTrafficPct() ? ModelRole.CHALLENGER : ModelRole.CHAMPION;
    }

    private ScoreRecord persist(ScoreRequest req, InferenceResult inference,
                                FeatureVector features, List<ReasonCodeItem> reasonCodes) {
        String featureJson = toJson(features.values());
        String reasonJson  = toJson(reasonCodes);

        ScoreRecord record = ScoreRecord.builder()
                .requestId(req.requestId())
                .userId(req.userId())
                .score(inference.score())
                .rawPd(BigDecimal.valueOf(inference.rawPd()))
                .scoreBand(ScoreBand.fromScore(inference.score()))
                .modelVersion(inference.modelVersion())
                .productType(req.productType())
                .featureSnapshot(featureJson)
                .reasonCodes(reasonJson)
                .source(ScoreSource.REAL_TIME)
                .modelRole(inference.modelRole())
                .computedAt(Instant.now())
                .consentRefId(req.consentReferenceId())
                .build();

        return scoreRecordRepository.save(record);
    }

    private ScoreResponse toResponse(ScoreRecord record, List<ReasonCodeItem> items, FeatureVector features) {
        return new ScoreResponse(
                record.getRequestId(),
                record.getUserId(),
                record.getScore(),
                record.getScoreBand(),
                record.getProductType(),
                record.getModelVersion(),
                record.getModelRole(),
                toDto(items),
                features.isThinFile(),
                record.getSource(),
                record.getComputedAt()
        );
    }

    private ScoreResponse toResponseFromRecord(ScoreRecord record) {
        List<ReasonCodeDto> reasonCodes = parseReasonCodes(record.getReasonCodes());
        return new ScoreResponse(
                record.getRequestId(),
                record.getUserId(),
                record.getScore(),
                record.getScoreBand(),
                record.getProductType(),
                record.getModelVersion(),
                record.getModelRole(),
                reasonCodes,
                false,
                record.getSource(),
                record.getComputedAt()
        );
    }

    private List<ReasonCodeDto> toDto(List<ReasonCodeItem> items) {
        return items.stream()
                .map(r -> new ReasonCodeDto(r.code(), r.description(), r.direction(), r.rank()))
                .toList();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private List<ReasonCodeDto> parseReasonCodes(String json) {
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, ReasonCodeDto.class));
        } catch (Exception e) {
            return List.of();
        }
    }

    public static class ScoreNotFoundException extends RuntimeException {
        public ScoreNotFoundException(String requestId) {
            super("Score not found for requestId: " + requestId);
        }
    }
}
