package io.nexuspay.workflow.adapter.in.rest.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateWorkflowRequest(
        @NotBlank String name,
        String description,
        @NotBlank String triggerType,
        String createdBy
) {}
