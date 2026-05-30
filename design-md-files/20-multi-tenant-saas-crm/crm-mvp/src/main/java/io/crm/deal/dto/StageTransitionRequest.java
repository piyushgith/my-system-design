package io.crm.deal.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record StageTransitionRequest(@NotNull UUID stageId) {}
