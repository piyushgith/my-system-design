package io.crm.deal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface StageRepository extends JpaRepository<Stage, UUID> {
    List<Stage> findByPipelinePipelineIdOrderByStageOrderAsc(UUID pipelineId);
}
