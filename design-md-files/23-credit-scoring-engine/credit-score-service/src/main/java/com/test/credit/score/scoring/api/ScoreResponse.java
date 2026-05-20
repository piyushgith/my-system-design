package com.test.credit.score.scoring.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.test.credit.score.scoring.domain.ModelRole;
import com.test.credit.score.scoring.domain.ProductType;
import com.test.credit.score.scoring.domain.ScoreBand;
import com.test.credit.score.scoring.domain.ScoreSource;

import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ScoreResponse(
        String requestId,
        String userId,
        int score,
        ScoreBand scoreBand,
        ProductType productType,
        String modelVersion,
        ModelRole modelRole,
        List<ReasonCodeDto> reasonCodes,
        boolean isThinFile,
        ScoreSource source,
        Instant computedAt
) {}
