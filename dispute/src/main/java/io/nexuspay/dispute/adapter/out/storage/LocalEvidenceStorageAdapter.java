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
        // Each path segment is sanitized to a single safe component — the raw
        // tenantId/disputeId/fileName are all caller-controlled, so without this
        // a value like "../../etc/passwd" would escape STORAGE_ROOT (path
        // traversal → arbitrary file write).
        String key = safeSegment(tenantId) + "/" + safeSegment(disputeId) + "/" + safeSegment(fileName);
        Path target = resolveWithinRoot(key);

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
        Path file = resolveWithinRoot(fileKey);
        try {
            return Files.newInputStream(file);
        } catch (IOException e) {
            throw new RuntimeException("Evidence file not found: " + fileKey, e);
        }
    }

    @Override
    public void delete(String fileKey) {
        try {
            Files.deleteIfExists(resolveWithinRoot(fileKey));
            log.info("Evidence deleted: key={}", fileKey);
        } catch (IOException e) {
            log.warn("Failed to delete evidence file: {}", fileKey, e);
        }
    }

    /** Reduces an untrusted value to a single safe path component. */
    private static String safeSegment(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Evidence path segment must not be blank");
        }
        // Keep only the final name and strip anything but a conservative charset.
        String name = Path.of(raw).getFileName().toString();
        String sanitized = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (sanitized.isBlank() || sanitized.equals(".") || sanitized.equals("..")) {
            throw new IllegalArgumentException("Invalid evidence path segment: " + raw);
        }
        return sanitized;
    }

    /** Resolves a key under STORAGE_ROOT and rejects any escape. */
    private static Path resolveWithinRoot(String key) {
        Path root = STORAGE_ROOT.toAbsolutePath().normalize();
        Path resolved = root.resolve(key).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Evidence path escapes storage root: " + key);
        }
        return resolved;
    }
}
