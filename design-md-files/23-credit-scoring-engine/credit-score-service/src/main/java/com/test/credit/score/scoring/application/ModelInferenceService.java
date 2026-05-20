package com.test.credit.score.scoring.application;

import com.test.credit.score.model.application.ModelRegistryService;
import com.test.credit.score.model.domain.ModelRegistration;
import com.test.credit.score.scoring.domain.FeatureVector;
import com.test.credit.score.scoring.domain.InferenceResult;
import com.test.credit.score.scoring.domain.ModelRole;
import com.test.credit.score.scoring.domain.ProductType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Simulated inference engine.
 * Replace map lookup with real OnnxRuntime session per model version in V1.
 *
 * Feature positions (index into double[]):
 *   0 bureau.cibil_score
 *   1 bureau.dpd_last_6m
 *   2 bureau.credit_utilization
 *   3 bureau.inquiry_count_last_90d
 *   4 behavior.upi_txn_count_last_30d
 *   5 behavior.avg_monthly_credit
 */
@Service
@RequiredArgsConstructor
public class ModelInferenceService {

    private final ModelRegistryService modelRegistryService;

    // Coefficients keyed by model version: [intercept, c0..c5]
    private static final Map<String, double[]> COEFFICIENTS = Map.of(
        // sim-v1.0 (champion)
        "sim-v1.0", new double[]{
            -2.0,        // intercept
            -0.0050,     // cibil_score:         higher = lower PD
             0.4000,     // dpd_last_6m:          DPDs raise PD
             1.5000,     // credit_utilization:   high util raises PD
             0.1500,     // inquiry_count_last_90d
            -0.0100,     // upi_txn_count_last_30d: activity lowers PD
            -0.0000020   // avg_monthly_credit:   higher income proxy lowers PD
        },
        // sim-v1.1 (challenger) — more lenient on utilization, tests if that improves Gini
        "sim-v1.1", new double[]{
            -2.0,
            -0.0050,
             0.3500,
             1.2000,
             0.1500,
            -0.0100,
            -0.0000020
        }
    );

    public InferenceResult infer(FeatureVector features, ModelRole role, ProductType productType) {
        ModelRegistration model = role == ModelRole.CHALLENGER
                ? modelRegistryService.getChallenger(productType)
                : modelRegistryService.getChampion(productType);

        List<String> featureOrder = model.featureOrderList();
        double[] x = features.toArray(featureOrder);
        double[] coeff = COEFFICIENTS.getOrDefault(model.getModelVersion(), COEFFICIENTS.get("sim-v1.0"));

        double logit = coeff[0]; // intercept
        for (int i = 0; i < x.length && i + 1 < coeff.length; i++) {
            logit += coeff[i + 1] * x[i];
        }

        double pd = sigmoid(logit);
        int score = pdToScore(pd);
        Map<String, Double> contributions = computeContributions(featureOrder, x, coeff);

        return new InferenceResult(score, pd, model.getModelVersion(), role, contributions);
    }

    private Map<String, Double> computeContributions(List<String> names, double[] x, double[] coeff) {
        Map<String, Double> result = new LinkedHashMap<>();
        for (int i = 0; i < names.size() && i + 1 < coeff.length; i++) {
            result.put(names.get(i), coeff[i + 1] * x[i]);
        }
        return result;
    }

    private double sigmoid(double logit) {
        return 1.0 / (1.0 + Math.exp(-logit));
    }

    private int pdToScore(double pd) {
        int raw = (int) Math.round(300 + (1.0 - pd) * 600);
        return Math.max(300, Math.min(900, raw));
    }
}
