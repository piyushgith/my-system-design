package com.test.credit.score.scoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.test.credit.score.config.ScoringProperties;
import com.test.credit.score.scoring.api.ScoreRequest;
import com.test.credit.score.scoring.api.ScoreResponse;
import com.test.credit.score.scoring.application.*;
import com.test.credit.score.scoring.domain.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScoringServiceTest {

    @Mock ScoreRecordRepository scoreRecordRepository;
    @Mock IdempotencyService idempotencyService;
    @Mock ScoreCacheService scoreCacheService;
    @Mock FeatureAssembler featureAssembler;
    @Mock ModelInferenceService modelInferenceService;
    @Mock ReasonCodeService reasonCodeService;

    ScoringService scoringService;

    @BeforeEach
    void setUp() {
        ScoringProperties props = new ScoringProperties();
        props.setChallengerTrafficPct(10);

        scoringService = new ScoringService(
                idempotencyService, scoreCacheService,
                featureAssembler, modelInferenceService, reasonCodeService,
                scoreRecordRepository, new ObjectMapper(), props,
                new SimpleMeterRegistry());
    }

    @Test
    void computeScore_goodBureauProfile_returnsExcellentBand() {
        String userId = "usr-test-001";
        String requestId = UUID.randomUUID().toString();

        Map<String, Double> features = Map.of(
                "bureau.cibil_score", 780.0,
                "bureau.dpd_last_6m", 0.0,
                "bureau.credit_utilization", 0.15,
                "bureau.inquiry_count_last_90d", 1.0,
                "behavior.upi_txn_count_last_30d", 60.0,
                "behavior.avg_monthly_credit", 120000.0
        );
        FeatureVector featureVector = new FeatureVector(userId, features, false);
        InferenceResult inferenceResult = new InferenceResult(
                780, 0.05, "sim-v1.0", ModelRole.CHAMPION,
                Map.of("bureau.credit_utilization", -0.2, "bureau.dpd_last_6m", 0.0));

        when(idempotencyService.get(requestId)).thenReturn(Optional.empty());
        when(scoreCacheService.get(userId, ProductType.PERSONAL_LOAN)).thenReturn(Optional.empty());
        when(featureAssembler.assemble(userId, ProductType.PERSONAL_LOAN)).thenReturn(featureVector);
        when(modelInferenceService.infer(featureVector, ModelRole.CHAMPION, ProductType.PERSONAL_LOAN))
                .thenReturn(inferenceResult);
        when(reasonCodeService.compute(featureVector, inferenceResult)).thenReturn(java.util.List.of());
        when(scoreRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ScoreRequest request = new ScoreRequest(requestId, userId, ProductType.PERSONAL_LOAN, false, null);
        ScoreResponse response = scoringService.computeScore(request);

        assertThat(response.score()).isEqualTo(780);
        assertThat(response.scoreBand()).isEqualTo(ScoreBand.EXCELLENT);
        assertThat(response.modelVersion()).isEqualTo("sim-v1.0");
        assertThat(response.source()).isEqualTo(ScoreSource.REAL_TIME);

        verify(scoreRecordRepository).save(any(ScoreRecord.class));
        verify(idempotencyService).put(eq(requestId), any(ScoreResponse.class));
    }

    @Test
    void computeScore_idempotentRequest_returnsCachedWithoutRecompute() {
        String requestId = UUID.randomUUID().toString();
        ScoreResponse cached = new ScoreResponse(requestId, "usr-001", 720,
                ScoreBand.EXCELLENT, ProductType.PERSONAL_LOAN, "sim-v1.0", ModelRole.CHAMPION,
                java.util.List.of(), false, ScoreSource.REAL_TIME, Instant.now());

        when(idempotencyService.get(requestId)).thenReturn(Optional.of(cached));

        ScoreRequest request = new ScoreRequest(requestId, "usr-001", ProductType.PERSONAL_LOAN, false, null);
        ScoreResponse response = scoringService.computeScore(request);

        assertThat(response).isEqualTo(cached);
        verifyNoInteractions(featureAssembler, modelInferenceService, scoreRecordRepository);
    }

    @Test
    void computeScore_thinFileUser_scoreStillComputedWithDefaults() {
        String userId = "usr-ntc-001";
        String requestId = UUID.randomUUID().toString();

        // Thin file: cibil_score = 0
        Map<String, Double> features = Map.of(
                "bureau.cibil_score", 0.0,
                "bureau.dpd_last_6m", 0.0,
                "bureau.credit_utilization", 0.5,
                "bureau.inquiry_count_last_90d", 0.0,
                "behavior.upi_txn_count_last_30d", 30.0,
                "behavior.avg_monthly_credit", 25000.0
        );
        FeatureVector featureVector = new FeatureVector(userId, features, true);
        InferenceResult inferenceResult = new InferenceResult(
                540, 0.4, "sim-v1.0", ModelRole.CHAMPION, Map.of());

        when(idempotencyService.get(requestId)).thenReturn(Optional.empty());
        when(scoreCacheService.get(userId, ProductType.PERSONAL_LOAN)).thenReturn(Optional.empty());
        when(featureAssembler.assemble(userId, ProductType.PERSONAL_LOAN)).thenReturn(featureVector);
        when(modelInferenceService.infer(featureVector, ModelRole.CHAMPION, ProductType.PERSONAL_LOAN))
                .thenReturn(inferenceResult);
        when(reasonCodeService.compute(any(), any())).thenReturn(java.util.List.of());
        when(scoreRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ScoreRequest request = new ScoreRequest(requestId, userId, ProductType.PERSONAL_LOAN, false, null);
        ScoreResponse response = scoringService.computeScore(request);

        assertThat(response.isThinFile()).isTrue();
        assertThat(response.score()).isEqualTo(540);
        assertThat(response.scoreBand()).isEqualTo(ScoreBand.VERY_POOR);
    }

    @Test
    void scoreBand_fromScore_correctBands() {
        assertThat(ScoreBand.fromScore(800)).isEqualTo(ScoreBand.EXCELLENT);
        assertThat(ScoreBand.fromScore(720)).isEqualTo(ScoreBand.GOOD);
        assertThat(ScoreBand.fromScore(670)).isEqualTo(ScoreBand.FAIR);
        assertThat(ScoreBand.fromScore(620)).isEqualTo(ScoreBand.POOR);
        assertThat(ScoreBand.fromScore(450)).isEqualTo(ScoreBand.VERY_POOR);
        assertThat(ScoreBand.fromScore(300)).isEqualTo(ScoreBand.VERY_POOR);
        assertThat(ScoreBand.fromScore(900)).isEqualTo(ScoreBand.EXCELLENT);
    }
}
