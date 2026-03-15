package io.nexuspay.reconciliation.adapter.out.file;

import io.nexuspay.reconciliation.application.port.out.SettlementFilePort;
import org.springframework.stereotype.Component;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.file.Path;

/**
 * Reads settlement files from the local filesystem.
 *
 * <p>Used for local development and testing. In production, SFTP or S3
 * adapters would be used instead.</p>
 *
 * @since 0.2.0 (Sprint 2.3)
 */
@Component
public class LocalSettlementFileAdapter implements SettlementFilePort {

    @Override
    public InputStream fetch(String path) {
        try {
            return new FileInputStream(Path.of(path).toFile());
        } catch (FileNotFoundException e) {
            throw new RuntimeException("Settlement file not found: " + path, e);
        }
    }

    @Override
    public String source() {
        return "local";
    }
}
