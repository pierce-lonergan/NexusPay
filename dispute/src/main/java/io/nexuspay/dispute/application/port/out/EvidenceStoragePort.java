package io.nexuspay.dispute.application.port.out;

import java.io.InputStream;

/**
 * Output port for evidence file storage (S3, MinIO, local filesystem).
 *
 * @since 0.2.4 (Sprint 2.4)
 */
public interface EvidenceStoragePort {

    /**
     * Stores an evidence file and returns the storage key.
     *
     * @param tenantId    tenant identifier for key namespacing
     * @param disputeId   dispute this evidence belongs to
     * @param fileName    original file name
     * @param content     file content stream
     * @param contentType MIME type
     * @return storage key (e.g., S3 object key)
     */
    String store(String tenantId, String disputeId, String fileName,
                 InputStream content, String contentType);

    /**
     * Retrieves an evidence file by its storage key.
     *
     * @param fileKey  the key returned by {@link #store}
     * @return file content stream
     */
    InputStream retrieve(String fileKey);

    /**
     * Deletes an evidence file.
     *
     * @param fileKey  the key returned by {@link #store}
     */
    void delete(String fileKey);
}
