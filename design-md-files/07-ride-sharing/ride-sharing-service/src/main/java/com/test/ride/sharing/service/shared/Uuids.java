package com.test.ride.sharing.service.shared;

import com.github.f4b6a3.uuid.UuidCreator;

import java.util.UUID;

/**
 * Central ID factory — all runtime entity IDs use UUID v7 (time-ordered, index-friendly).
 */
public final class Uuids {

    private Uuids() {
    }

    public static UUID v7() {
        return UuidCreator.getTimeOrderedEpoch();
    }
}
