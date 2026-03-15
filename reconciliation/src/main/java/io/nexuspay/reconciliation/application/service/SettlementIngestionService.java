package io.nexuspay.reconciliation.application.service;

import io.nexuspay.reconciliation.application.port.out.ReconciliationRepository;
import io.nexuspay.reconciliation.application.port.out.SettlementFilePort;
import io.nexuspay.reconciliation.application.port.out.SettlementParserPort;
import io.nexuspay.reconciliation.domain.ReconciliationRun;
import io.nexuspay.reconciliation.domain.SettlementRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Service for ingesting settlement files from PSP providers.
 *
 * <p>Handles the pipeline: fetch file → parse → persist records → create run.</p>
 *
 * @since 0.2.0 (Sprint 2.3)
 */
@Service
public class SettlementIngestionService {

    private static final Logger log = LoggerFactory.getLogger(SettlementIngestionService.class);

    private final ReconciliationRepository repository;
    private final Map<String, SettlementParserPort> parsers;
    private final Map<String, SettlementFilePort> fileSources;

    public SettlementIngestionService(ReconciliationRepository repository,
                                      List<SettlementParserPort> parserList,
                                      List<SettlementFilePort> fileSourceList) {
        this.repository = repository;
        this.parsers = parserList.stream()
                .collect(Collectors.toMap(SettlementParserPort::provider, Function.identity()));
        this.fileSources = fileSourceList.stream()
                .collect(Collectors.toMap(SettlementFilePort::source, Function.identity()));
    }

    /**
     * Ingests a settlement file from the given source for the specified provider.
     *
     * @param tenantId    the tenant context
     * @param provider    the PSP provider name (must match a registered parser)
     * @param source      the file source ("sftp", "s3", "local")
     * @param path        the file path or S3 key
     * @return the created reconciliation run with parsed settlement records
     */
    @Transactional
    public ReconciliationRun ingest(String tenantId, String provider, String source, String path) {
        SettlementParserPort parser = parsers.get(provider);
        if (parser == null) {
            throw new IllegalArgumentException("No parser registered for provider: " + provider);
        }

        SettlementFilePort fileSource = fileSources.get(source);
        if (fileSource == null) {
            throw new IllegalArgumentException("No file source registered: " + source);
        }

        // Create reconciliation run
        String fileName = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
        ReconciliationRun run = ReconciliationRun.create(tenantId, provider, fileName);
        repository.saveRun(run);

        log.info("Ingesting settlement file: provider={}, source={}, path={}, runId={}",
                provider, source, path, run.getId());

        // Fetch and parse
        InputStream fileContent = fileSource.fetch(path);
        List<SettlementRecord> records = parser.parse(fileContent, tenantId, run.getId());

        // Persist settlement records
        repository.saveAllSettlementRecords(records);

        log.info("Ingested {} settlement records for run: {}", records.size(), run.getId());

        return run;
    }

    /**
     * Ingests settlement records from a direct input stream (e.g., file upload).
     */
    @Transactional
    public ReconciliationRun ingestFromStream(String tenantId, String provider,
                                              String fileName, InputStream input) {
        SettlementParserPort parser = parsers.get(provider);
        if (parser == null) {
            throw new IllegalArgumentException("No parser registered for provider: " + provider);
        }

        ReconciliationRun run = ReconciliationRun.create(tenantId, provider, fileName);
        repository.saveRun(run);

        List<SettlementRecord> records = parser.parse(input, tenantId, run.getId());
        repository.saveAllSettlementRecords(records);

        log.info("Ingested {} settlement records from upload for run: {}", records.size(), run.getId());

        return run;
    }
}
