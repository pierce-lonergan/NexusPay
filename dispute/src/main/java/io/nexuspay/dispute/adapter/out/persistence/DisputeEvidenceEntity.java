package io.nexuspay.dispute.adapter.out.persistence;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * JPA entity mapped to the {@code dispute_evidence} table.
 *
 * @since 0.2.4 (Sprint 2.4)
 */
@Entity
@Table(name = "dispute_evidence")
public class DisputeEvidenceEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "dispute_id", nullable = false, length = 64)
    private String disputeId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "evidence_type", nullable = false, length = 32)
    private String evidenceType;

    @Column(name = "file_key", length = 256)
    private String fileKey;

    @Column(name = "file_name", length = 256)
    private String fileName;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    // -- Getters & setters --

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getDisputeId() { return disputeId; }
    public void setDisputeId(String disputeId) { this.disputeId = disputeId; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getEvidenceType() { return evidenceType; }
    public void setEvidenceType(String evidenceType) { this.evidenceType = evidenceType; }
    public String getFileKey() { return fileKey; }
    public void setFileKey(String fileKey) { this.fileKey = fileKey; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Instant getUploadedAt() { return uploadedAt; }
    public void setUploadedAt(Instant uploadedAt) { this.uploadedAt = uploadedAt; }
}
