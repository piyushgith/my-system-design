package com.test.credit.score.scoring.domain;

public enum ScoreBand {
    EXCELLENT(750, 900),
    GOOD(700, 749),
    FAIR(650, 699),
    POOR(600, 649),
    VERY_POOR(300, 599);

    private final int min;
    private final int max;

    ScoreBand(int min, int max) {
        this.min = min;
        this.max = max;
    }

    public static ScoreBand fromScore(int score) {
        for (ScoreBand band : values()) {
            if (score >= band.min && score <= band.max) return band;
        }
        return VERY_POOR;
    }
}
