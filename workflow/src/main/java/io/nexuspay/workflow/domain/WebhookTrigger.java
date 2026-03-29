package io.nexuspay.workflow.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain model representing an inbound webhook trigger for a workflow.
 * Each webhook trigger has a unique URL path and secret for verification.
 *
 * @since 0.4.3 (Sprint 4.4)
 */
public class WebhookTrigger {

    private String id;
    private String tenantId;
    private String workflowId;
    private String urlPath;
    private String secret;
    private boolean active;
    private Instant createdAt;

    public static WebhookTrigger create(String tenantId, String workflowId) {
        WebhookTrigger trigger = new WebhookTrigger();
        trigger.id = "wht_" + UUID.randomUUID().toString().replace("-", "");
        trigger.tenantId = tenantId;
        trigger.workflowId = workflowId;
        trigger.urlPath = "/webhooks/workflows/" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        trigger.secret = UUID.randomUUID().toString().replace("-", "");
        trigger.active = true;
        trigger.createdAt = Instant.now();
        return trigger;
    }

    public void deactivate() {
        this.active = false;
    }

    public void activate() {
        this.active = true;
    }

    public void regenerateSecret() {
        this.secret = UUID.randomUUID().toString().replace("-", "");
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getWorkflowId() { return workflowId; }
    public void setWorkflowId(String workflowId) { this.workflowId = workflowId; }

    public String getUrlPath() { return urlPath; }
    public void setUrlPath(String urlPath) { this.urlPath = urlPath; }

    public String getSecret() { return secret; }
    public void setSecret(String secret) { this.secret = secret; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
