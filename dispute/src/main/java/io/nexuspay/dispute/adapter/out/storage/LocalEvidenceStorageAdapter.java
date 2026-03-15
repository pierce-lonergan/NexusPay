package io.nexuspay.dispute.adapter.out.storage;

import io.nexuspay.dispute.application.port.out.EvidenceStoragePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Local filesystem evidence storage adapter for development.
 *
 * <p>Stores evidence files under a local directory.  Production deployments
 * replace this with an S3-backed implementation.</p>
 *
 * @since 0.2.4 (Sprint 2.4)
 */
@Component
public class LocalEvidenceStorageAdapter implements EvidenceStoragePort {

    private static final Logger log = LoggerFactory.getLogger(LocalEvidenceStorageAdapter.class);
    private static final Path STORAGE_ROOT = Path.of(System.getProperty("java.io.tmpdir"), "nexuspay-evidence");

    @Override
    public String store(String tenantId, String disputeId, String fileName,
                        InputStream content, String contentType) {
        String key = tenantId + "/" + disputeId + "/" + fileName;
        Path target = STORAGE_ROOT.resolve(key);

        try {
            Files.createDirectories(target.getParent());
            Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);
            log.info("Evidence stored: key={}", key);
        } catch (IOException e) {
            throw new RuntimeException("Failed to store evidence file: " + key, e);
        }
        return key;
    }

    @Override
    public InputStream retrieve(String fileKey) {
        Path file = STORAGE_ROOT.resolve(fileKey);
        try {
            return Files.newInputStream(file);
        } catch (IOException e) {
            throw new RuntimeException("Evidence file not found: " + fileKey, e);
        }
    }

    @Override
    public void delete(String fileKey) {
        try {
            Files.deleteIfExists(STORAGE_ROOT.resolve(fileKey));
            log.info("Evidence deleted: key={}", fileKey);
        } catch (IOException e) {
            log.warn("Failed to delete evidence file: {}", fileKey, e);
        }
    }
}
