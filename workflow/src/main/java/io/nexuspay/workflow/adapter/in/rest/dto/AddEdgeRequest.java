package io.nexuspay.workflow.adapter.in.rest.dto;

import jakarta.validation.constraints.NotBlank;

public record AddEdgeRequest(
        @NotBlank String sourceNodeId,
        @NotBlank String targetNodeId,
        String conditionExpression,
        String label
) {}
