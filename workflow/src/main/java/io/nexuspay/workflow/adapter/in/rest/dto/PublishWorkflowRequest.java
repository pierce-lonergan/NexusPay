package io.nexuspay.workflow.adapter.in.rest.dto;

public record PublishWorkflowRequest(
        String publishedBy,
        String changeDescription
) {}
