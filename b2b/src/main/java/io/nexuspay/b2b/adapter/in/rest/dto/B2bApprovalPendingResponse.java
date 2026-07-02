package io.nexuspay.b2b.adapter.in.rest.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * GAP-068: body for the maker-checker approval lifecycle on b2b approve endpoints — the INT-2
 * refund 202 contract mirror ({@code gateway ApprovalResponse}): {@code requires_approval=true} +
 * the pending approval id + the configured threshold on the 202 path; also reused (with the
 * threshold fields dropped by NON_NULL) as the reject-review response. Field names are literal
 * snake_case, matching the gateway approval contract this mirrors.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record B2bApprovalPendingResponse(
        Boolean requires_approval,
        String approval_id,
        String status,
        String action,
        String resource_id,
        Long approval_threshold
) {}
