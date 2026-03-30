package io.nexuspay.workflow.adapter.in.rest.dto;

public record UpdateWorkflowRequest(
        String name,
        String description,
        String triggerType,
        String triggerConfig
) {}
