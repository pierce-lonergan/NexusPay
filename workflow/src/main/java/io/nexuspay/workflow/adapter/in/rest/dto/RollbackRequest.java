package io.nexuspay.workflow.adapter.in.rest.dto;

public record RollbackRequest(
        int targetVersion,
        String publishedBy
) {}
