package com.test.credit.score.scoring.domain;

import java.util.Map;

public record InferenceResult(
        int score,
        double rawPd,
        String modelVersion,
        ModelRole modelRole,
        Map<String, Double> featureContributions   // featureName -> contribution to logit (for reason codes)
) {}
