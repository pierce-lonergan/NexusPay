package io.nexuspay.iam.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "audit_log")
public class AuditLogEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 128)
    private String actor;

    @Column(nullable = false, length = 64)
    private String action;

    @Column(name = "resource_type", length = 64)
    private String resourceType;

    @Column(name = "resource_id", length = 64)
    private String resourceId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> details;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(nullable = false)
    private Instant timestamp;

    protected AuditLogEntity() {}

    public AuditLogEntity(String id, String actor, String action, String resourceType,
                           String resourceId, Map<String, Object> details, String ipAddress,
                           String tenantId, Instant timestamp) {
        this.id = id;
        this.actor = actor;
        this.action = action;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.details = details;
        this.ipAddress = ipAddress;
        this.tenantId = tenantId;
        this.timestamp = timestamp;
    }

    public String getId() { return id; }
    public String getActor() { return actor; }
    public String getAction() { return action; }
    public String getResourceType() { return resourceType; }
    public String getResourceId() { return resourceId; }
    public Map<String, Object> getDetails() { return details; }
    public String getIpAddress() { return ipAddress; }
    public String getTenantId() { return tenantId; }
    public Instant getTimestamp() { return timestamp; }
}
