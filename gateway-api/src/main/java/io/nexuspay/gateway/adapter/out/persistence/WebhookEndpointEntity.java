package io.nexuspay.gateway.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "webhook_endpoints")
public class WebhookEndpointEntity {

    @Id
    private String id;

    @Column(nullable = false)
    private String url;

    private String description;

    @Column(nullable = false)
    private String secret;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]", nullable = false)
    private List<String> events;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WebhookEndpointEntity() {
    }

    public WebhookEndpointEntity(String id, String url, String description, String secret,
                                  List<String> events, String tenantId) {
        this.id = id;
        this.url = url;
        this.description = description;
        this.secret = secret;
        this.events = events;
        this.tenantId = tenantId;
        this.enabled = true;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public String getId() { return id; }
    public String getUrl() { return url; }
    public String getDescription() { return description; }
    public String getSecret() { return secret; }
    public List<String> getEvents() { return events; }
    public String getTenantId() { return tenantId; }
    public boolean isEnabled() { return enabled; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        this.updatedAt = Instant.now();
    }
}
