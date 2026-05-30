package io.crm.deal;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@RestController
@RequestMapping("/v1/pipelines")
public class PipelineController {

    private final PipelineRepository pipelineRepository;
    private final StageRepository stageRepository;

    public PipelineController(PipelineRepository pipelineRepository, StageRepository stageRepository) {
        this.pipelineRepository = pipelineRepository;
        this.stageRepository = stageRepository;
    }

    @GetMapping
    public List<Pipeline> listPipelines() {
        return pipelineRepository.findAll();
    }

    @GetMapping("/{pipelineId}/stages")
    public List<Stage> listStages(@PathVariable UUID pipelineId) {
        pipelineRepository.findById(pipelineId)
                .orElseThrow(() -> new NoSuchElementException("Pipeline not found: " + pipelineId));
        return stageRepository.findByPipelinePipelineIdOrderByStageOrderAsc(pipelineId);
    }
}
