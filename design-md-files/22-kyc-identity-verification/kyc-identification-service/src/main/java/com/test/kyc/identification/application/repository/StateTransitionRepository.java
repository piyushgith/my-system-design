package com.test.kyc.identification.application.repository;

import com.test.kyc.identification.application.domain.StateTransition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface StateTransitionRepository extends JpaRepository<StateTransition, UUID> {

    List<StateTransition> findByApplicationIdOrderByOccurredAtAsc(UUID applicationId);
}
