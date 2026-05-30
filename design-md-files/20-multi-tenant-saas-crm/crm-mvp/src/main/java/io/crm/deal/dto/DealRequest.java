package io.crm.deal.dto;

import io.crm.deal.DealStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record DealRequest(
        @NotBlank String title,
        BigDecimal value,
        String currency,
        @NotNull UUID pipelineId,
        @NotNull UUID stageId,
        UUID contactId,
        @NotNull UUID ownerId,
        LocalDate expectedCloseDate,
        DealStatus status
) {}
