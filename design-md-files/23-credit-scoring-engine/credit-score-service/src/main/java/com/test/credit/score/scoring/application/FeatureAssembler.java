package com.test.credit.score.scoring.application;

import com.test.credit.score.feature.application.FeatureStoreService;
import com.test.credit.score.scoring.domain.FeatureVector;
import com.test.credit.score.scoring.domain.ProductType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class FeatureAssembler {

    private final FeatureStoreService featureStoreService;

    public FeatureVector assemble(String userId, ProductType productType) {
        Map<String, Double> values = featureStoreService.loadFeatures(userId);
        boolean isThinFile = values.getOrDefault("bureau.cibil_score", 0.0) == 0.0;
        return new FeatureVector(userId, values, isThinFile);
    }
}
