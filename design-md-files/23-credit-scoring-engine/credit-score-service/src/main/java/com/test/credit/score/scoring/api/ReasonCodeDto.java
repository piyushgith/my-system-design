package com.test.credit.score.scoring.api;

public record ReasonCodeDto(
        String code,
        String description,
        String direction,
        int rank
) {}
