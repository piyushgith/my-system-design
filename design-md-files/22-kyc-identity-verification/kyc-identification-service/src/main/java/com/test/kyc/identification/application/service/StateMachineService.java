package com.test.kyc.identification.application.service;

import com.test.kyc.identification.application.domain.KycApplication;
import com.test.kyc.identification.application.domain.KycStatus;
import com.test.kyc.identification.application.domain.StateTransition;
import com.test.kyc.identification.application.repository.KycApplicationRepository;
import com.test.kyc.identification.application.repository.StateTransitionRepository;
import com.test.kyc.identification.common.exception.InvalidStateTransitionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class StateMachineService {

    private final KycApplicationRepository applicationRepository;
    private final StateTransitionRepository transitionRepository;

    // Valid transitions: from -> set of allowed next states
    private static final Map<KycStatus, Set<KycStatus>> ALLOWED_TRANSITIONS = Map.ofEntries(
            Map.entry(KycStatus.SUBMITTED, Set.of(KycStatus.DOCUMENT_VERIFICATION_PENDING)),
            Map.entry(KycStatus.DOCUMENT_VERIFICATION_PENDING, Set.of(
                    KycStatus.DOCUMENT_VERIFIED, KycStatus.DOCUMENT_REJECTED)),
            Map.entry(KycStatus.DOCUMENT_VERIFIED, Set.of(KycStatus.LIVENESS_PENDING)),
            Map.entry(KycStatus.DOCUMENT_REJECTED, Set.of(KycStatus.MANUAL_REVIEW)),
            Map.entry(KycStatus.LIVENESS_PENDING, Set.of(
                    KycStatus.LIVENESS_PASSED, KycStatus.LIVENESS_FAILED)),
            Map.entry(KycStatus.LIVENESS_PASSED, Set.of(KycStatus.WATCHLIST_SCREENING)),
            Map.entry(KycStatus.LIVENESS_FAILED, Set.of(KycStatus.MANUAL_REVIEW)),
            Map.entry(KycStatus.WATCHLIST_SCREENING, Set.of(
                    KycStatus.WATCHLIST_CLEAR, KycStatus.WATCHLIST_HIT)),
            Map.entry(KycStatus.WATCHLIST_CLEAR, Set.of(KycStatus.APPROVED)),
            Map.entry(KycStatus.WATCHLIST_HIT, Set.of(KycStatus.MANUAL_REVIEW)),
            Map.entry(KycStatus.MANUAL_REVIEW, Set.of(KycStatus.APPROVED, KycStatus.REJECTED))
    );

    /**
     * Validates the transition, updates the application status, and appends to audit log.
     * All within one transaction — status + audit trail are always consistent.
     */
    @Transactional
    public void transition(KycApplication application,
                           KycStatus toStatus,
                           String triggerSource,
                           String triggeredBy,
                           String reason) {
        KycStatus fromStatus = application.getStatus();

        validateTransition(fromStatus, toStatus, application);

        var transition = StateTransition.of(
                application.getApplicationId(),
                fromStatus,
                toStatus,
                triggerSource,
                triggeredBy,
                reason
        );

        application.setStatus(toStatus);

        if (toStatus == KycStatus.APPROVED) {
            application.setApprovedAt(java.time.Instant.now());
        } else if (toStatus == KycStatus.REJECTED) {
            application.setRejectedAt(java.time.Instant.now());
        }

        applicationRepository.save(application);
        transitionRepository.save(transition);

        log.info("KYC state transition: app={} {}→{} by={} reason={}",
                application.getApplicationId(), fromStatus, toStatus, triggeredBy, reason);
    }

    private void validateTransition(KycStatus from, KycStatus to, KycApplication application) {
        if (from.isTerminal()) {
            throw new InvalidStateTransitionException(
                    "Application %s is in terminal state %s — no further transitions allowed"
                            .formatted(application.getApplicationId(), from));
        }

        Set<KycStatus> allowed = ALLOWED_TRANSITIONS.getOrDefault(from, Set.of());
        if (!allowed.contains(to)) {
            throw new InvalidStateTransitionException(
                    "Transition %s → %s not allowed for application %s"
                            .formatted(from, to, application.getApplicationId()));
        }
    }
}
