package com.test.ride.sharing.service.trip;

import com.test.ride.sharing.service.web.error.BusinessRuleException;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public final class TripStateMachine {

    private static final Map<TripStatus, Set<TripStatus>> TRANSITIONS = Map.of(
            TripStatus.REQUESTED, EnumSet.of(TripStatus.MATCHING, TripStatus.CANCELLED),
            TripStatus.MATCHING, EnumSet.of(TripStatus.DRIVER_MATCHED, TripStatus.CANCELLED, TripStatus.REQUESTED),
            TripStatus.DRIVER_MATCHED, EnumSet.of(TripStatus.DRIVER_ARRIVED, TripStatus.CANCELLED, TripStatus.REASSIGNMENT),
            TripStatus.REASSIGNMENT, EnumSet.of(TripStatus.MATCHING),
            TripStatus.DRIVER_ARRIVED, EnumSet.of(TripStatus.IN_PROGRESS, TripStatus.CANCELLED),
            TripStatus.IN_PROGRESS, EnumSet.of(TripStatus.COMPLETED, TripStatus.DISPUTED),
            TripStatus.DISPUTED, EnumSet.of(TripStatus.COMPLETED, TripStatus.CANCELLED)
    );

    private TripStateMachine() {
    }

    public static void validateTransition(TripStatus current, TripStatus next) {
        Set<TripStatus> allowed = TRANSITIONS.getOrDefault(current, Set.of());
        if (!allowed.contains(next)) {
            throw new BusinessRuleException("INVALID_STATE_TRANSITION",
                    "Cannot transition trip from " + current + " to " + next);
        }
    }
}
