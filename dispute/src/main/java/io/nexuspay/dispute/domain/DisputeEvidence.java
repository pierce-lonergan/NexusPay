package io.nexuspay.dispute.domain;

import java.time.Instant;

/**
 * An evidence item attached to a dispute.
 *
 * <p>Evidence files are stored in object storage (S3 / MinIO) and referenced
 * by {@code fileKey}.  Metadata is persisted alongside the dispute.</p>
 *
 * @since 0.2.4 (Sprint 2.4)
 */
public class DisputeEvidence {

    private String id;
    private String disputeId;
    private String tenantId;
    private DisputeEvidenceType evidenceType;
    private String fileKey;
    private String fileName;
    private Long fileSize;
    private String description;
    private Instant uploadedAt;

    public DisputeEvidence() {
    }

    public DisputeEvidence(String id, String disputeId, String tenantId,
                           DisputeEvidenceType evidenceType, String fileKey,
                           String fileName, Long fileSize, String description) {
        this.id = id;
        this.disputeId = disputeId;
        this.tenantId = tenantId;
        this.evidenceType = evidenceType;
        this.fileKey = fileKey;
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.description = description;
        this.uploadedAt = Instant.now();
    }

    // -- Getters & setters --

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getDisputeId() { return disputeId; }
    public void setDisputeId(String disputeId) { this.disputeId = disputeId; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public DisputeEvidenceType getEvidenceType() { return evidenceType; }
    public void setEvidenceType(DisputeEvidenceType evidenceType) { this.evidenceType = evidenceType; }

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
