package com.test.credit.score.scoring.domain;

import java.util.List;
import java.util.Map;

public record FeatureVector(
        String userId,
        Map<String, Double> values,
        boolean isThinFile
) {
    /** Returns ordered double array matching the model's expected feature positions. */
    public double[] toArray(List<String> featureOrder) {
        return featureOrder.stream()
                .mapToDouble(name -> values.getOrDefault(name, 0.0))
                .toArray();
    }
}
