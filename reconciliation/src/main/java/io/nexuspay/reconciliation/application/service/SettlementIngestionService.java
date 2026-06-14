package io.nexuspay.reconciliation.application.service;

import io.nexuspay.reconciliation.application.port.out.ReconciliationRepository;
import io.nexuspay.reconciliation.application.port.out.SettlementFilePort;
import io.nexuspay.reconciliation.application.port.out.SettlementParserPort;
import io.nexuspay.reconciliation.application.port.out.SettlementParserPort.ParseResult;
import io.nexuspay.reconciliation.domain.ReconciliationRun;
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
    private final ParseFailureRecorder parseFailureRecorder;
    private final Map<String, SettlementParserPort> parsers;
    private final Map<String, SettlementFilePort> fileSources;

    public SettlementIngestionService(ReconciliationRepository repository,
                                      ParseFailureRecorder parseFailureRecorder,
                                      List<SettlementParserPort> parserList,
                                      List<SettlementFilePort> fileSourceList) {
        this.repository = repository;
        this.parseFailureRecorder = parseFailureRecorder;
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
        ParseResult result = parser.parse(fileContent, tenantId, run.getId());

        // Persist settlement records
        repository.saveAllSettlementRecords(result.records());

        // B-015: every row that could not be parsed/validated becomes a durable
        // reconciliation exception — never a silent drop. FIX 2: committed in a
        // SEPARATE (REQUIRES_NEW) transaction so a downstream rollback can never
        // erase it.
        parseFailureRecorder.record(run, result.failures());

        log.info("Ingested {} settlement records ({} parse failures) for run: {}",
                result.records().size(), result.failures().size(), run.getId());

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

        ParseResult result = parser.parse(input, tenantId, run.getId());
        repository.saveAllSettlementRecords(result.records());

        // B-015: persist a durable exception for each unparseable row. FIX 2:
        // committed in a SEPARATE (REQUIRES_NEW) transaction so a downstream
        // rollback can never erase it.
        parseFailureRecorder.record(run, result.failures());

        log.info("Ingested {} settlement records ({} parse failures) from upload for run: {}",
                result.records().size(), result.failures().size(), run.getId());

        return run;
    }
}
