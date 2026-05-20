package com.test.kyc.identification.application.api.dto;

import com.test.kyc.identification.application.domain.StateTransition;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TransitionHistoryResponse(
        UUID applicationId,
        List<TransitionEntry> transitions
) {
    public record TransitionEntry(
            String fromStatus,
            String toStatus,
            String triggerSource,
            String triggeredBy,
            String reason,
            Instant occurredAt
    ) {
        public static TransitionEntry from(StateTransition t) {
            return new TransitionEntry(
                    t.getFromStatus(),
                    t.getToStatus(),
                    t.getTriggerSource(),
                    t.getTriggeredBy(),
                    t.getReason(),
                    t.getOccurredAt()
            );
        }
    }
}
