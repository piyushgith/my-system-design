package io.crm.deal;

import io.crm.common.PageResponse;
import io.crm.deal.dto.DealRequest;
import io.crm.deal.dto.DealResponse;
import io.crm.deal.dto.StageTransitionRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/v1/deals")
public class DealController {

    private final DealService dealService;

    public DealController(DealService dealService) {
        this.dealService = dealService;
    }

    @GetMapping
    public PageResponse<DealResponse> list(
            @RequestParam(required = false) DealStatus status,
            @RequestParam(required = false) UUID ownerId,
            @RequestParam(required = false) UUID pipelineId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return dealService.list(status, ownerId, pipelineId, page, pageSize);
    }

    @GetMapping("/{id}")
    public DealResponse get(@PathVariable UUID id) {
        return dealService.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DealResponse create(@Valid @RequestBody DealRequest req) {
        return dealService.create(req);
    }

    @PatchMapping("/{id}")
    public DealResponse update(@PathVariable UUID id, @Valid @RequestBody DealRequest req) {
        return dealService.update(id, req);
    }

    @PostMapping("/{id}/stage-transition")
    public DealResponse moveStage(@PathVariable UUID id, @Valid @RequestBody StageTransitionRequest req) {
        return dealService.moveStage(id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        dealService.delete(id);
    }
}
