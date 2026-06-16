package io.nexuspay.gateway.adapter.in.rest.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Pending approval object")
public record ApprovalResponse(
        @Schema(description = "Approval request ID")
        String id,

        @Schema(description = "Action requiring approval (e.g., refund)")
        String action,

        @Schema(description = "Resource type (e.g., Payment)")
        String resource_type,

        @Schema(description = "Resource ID")
        String resource_id,

        @Schema(description = "Approval status: pending_approval, approved, rejected")
        String status,

        @Schema(description = "User who requested the action")
        String requested_by,

        @Schema(description = "Admin who reviewed")
        String reviewed_by,

        @Schema(description = "Action payload details")
        Map<String, Object> payload,

        @Schema(description = "When the approval was requested")
        Instant created_at,

        @Schema(description = "When the approval was reviewed")
        Instant reviewed_at,

        @Schema(description = "True when this refund needs maker-checker approval")
        Boolean requires_approval,

        @Schema(description = "Approval threshold in minor units")
        Long approval_threshold
) {
}
