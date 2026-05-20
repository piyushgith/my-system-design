package com.test.kyc.identification.vendor;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class WatchlistResult {
    private final boolean clear;
    private final List<WatchlistHit> hits;

    @Getter
    @Builder
    public static class WatchlistHit {
        private final String listName;
        private final String matchedName;
        private final double matchScore;
        private final String hitType;
    }
}
