package io.crm.deal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PipelineRepository extends JpaRepository<Pipeline, UUID> {
    Optional<Pipeline> findByDefaultPipelineTrue();
}
