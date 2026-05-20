package com.test.credit.score.feature.api;

import jakarta.validation.constraints.NotEmpty;
import java.util.Map;

public record FeatureSeedRequest(
        @NotEmpty Map<String, String> features
) {}
