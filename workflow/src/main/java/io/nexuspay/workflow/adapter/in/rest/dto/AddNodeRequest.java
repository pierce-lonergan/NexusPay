package io.nexuspay.workflow.adapter.in.rest.dto;

import jakarta.validation.constraints.NotBlank;

public record AddNodeRequest(
        @NotBlank String nodeType,
        @NotBlank String label,
        String config,
        double positionX,
        double positionY
) {}
