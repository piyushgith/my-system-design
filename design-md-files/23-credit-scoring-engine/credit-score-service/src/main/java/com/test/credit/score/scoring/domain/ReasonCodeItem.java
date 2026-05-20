package com.test.credit.score.scoring.domain;

public record ReasonCodeItem(
        String code,
        String description,
        String direction,   // NEGATIVE | POSITIVE
        int rank
) {}
