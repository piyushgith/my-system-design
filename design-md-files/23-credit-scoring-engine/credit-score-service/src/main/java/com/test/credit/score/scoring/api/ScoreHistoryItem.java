package com.test.credit.score.scoring.api;

import com.test.credit.score.scoring.domain.ModelRole;
import com.test.credit.score.scoring.domain.ProductType;
import com.test.credit.score.scoring.domain.ScoreBand;
import com.test.credit.score.scoring.domain.ScoreSource;

import java.time.Instant;

public record ScoreHistoryItem(
        String requestId,
        int score,
        ScoreBand scoreBand,
        String modelVersion,
        ProductType productType,
        ScoreSource source,
        ModelRole modelRole,
        Instant computedAt
) {}
