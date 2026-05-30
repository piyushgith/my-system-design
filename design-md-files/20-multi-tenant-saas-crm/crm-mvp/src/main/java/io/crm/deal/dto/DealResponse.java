package io.crm.deal.dto;

import io.crm.deal.Deal;
import io.crm.deal.DealStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record DealResponse(
        UUID dealId,
        String title,
        BigDecimal value,
        String currency,
        UUID pipelineId,
        String pipelineName,
        UUID stageId,
        String stageName,
        int stageProbability,
        UUID contactId,
        UUID ownerId,
        String ownerName,
        LocalDate expectedCloseDate,
        DealStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime closedAt
) {
    public static DealResponse from(Deal d) {
        return new DealResponse(
                d.getDealId(),
                d.getTitle(),
                d.getValue(),
                d.getCurrency(),
                d.getPipeline().getPipelineId(),
                d.getPipeline().getName(),
                d.getStage().getStageId(),
                d.getStage().getName(),
                d.getStage().getProbability(),
                d.getContact() != null ? d.getContact().getContactId() : null,
                d.getOwner().getUserId(),
                d.getOwner().getFullName(),
                d.getExpectedCloseDate(),
                d.getStatus(),
                d.getCreatedAt(),
                d.getUpdatedAt(),
                d.getClosedAt()
        );
    }
}
