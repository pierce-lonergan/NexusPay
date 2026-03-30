package io.nexuspay.workflow.adapter.in.rest.dto;

import java.time.Instant;
import java.util.List;

public record WorkflowResponse(
        String workflowId, String name, String description, String status,
        int version, String triggerType, String triggerConfig,
        List<NodeDto> nodes, List<EdgeDto> edges,
        String createdBy, Instant createdAt, Instant updatedAt
) {
    public record NodeDto(String nodeId, String nodeType, String label, String config,
                           double positionX, double positionY) {}
    public record EdgeDto(String edgeId, String sourceNodeId, String targetNodeId,
                           String conditionExpression, String label) {}
}
