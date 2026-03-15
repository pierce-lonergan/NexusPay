package io.nexuspay.reconciliation.application.port.out;

import java.io.InputStream;

/**
 * Port for fetching settlement files from external sources (SFTP, S3, local).
 *
 * @since 0.2.0 (Sprint 2.3)
 */
public interface SettlementFilePort {

    /**
     * Fetches a settlement file by its path/key.
     *
     * @param path the file path or S3 key
     * @return input stream of the file content
     */
    InputStream fetch(String path);

    /**
     * Returns the source identifier (e.g., "sftp", "s3", "local").
     */
    String source();
}
