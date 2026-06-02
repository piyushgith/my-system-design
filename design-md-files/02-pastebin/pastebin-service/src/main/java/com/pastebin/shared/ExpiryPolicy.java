package com.pastebin.shared;

import java.time.Duration;
import java.time.Instant;

public enum ExpiryPolicy {
    ONE_HOUR(Duration.ofHours(1)),
    ONE_DAY(Duration.ofDays(1)),
    ONE_WEEK(Duration.ofDays(7)),
    ONE_MONTH(Duration.ofDays(30)),
    NEVER(null);

    private final Duration duration;

    ExpiryPolicy(Duration duration) {
        this.duration = duration;
    }

    public Duration duration() {
        return duration;
    }

    public Instant toExpiryInstant(Instant now) {
        return duration == null ? null : now.plus(duration);
    }
}
