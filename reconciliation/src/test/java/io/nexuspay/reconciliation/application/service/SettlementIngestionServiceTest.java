package io.nexuspay.reconciliation.application.service;

import io.nexuspay.reconciliation.adapter.out.parser.StripeCsvParser;
import io.nexuspay.reconciliation.application.port.out.ReconciliationRepository;
import io.nexuspay.reconciliation.application.port.out.SettlementFilePort;
import io.nexuspay.reconciliation.application.port.out.SettlementParserPort;
import io.nexuspay.reconciliation.application.port.out.SettlementParserPort.ParseFailure;
import io.nexuspay.reconciliation.domain.MatchResult;
import io.nexuspay.reconciliation.domain.ReconciliationException;
import io.nexuspay.reconciliation.domain.ReconciliationRun;
import io.nexuspay.reconciliation.domain.SettlementRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-015: proves the ingestion service turns every unparseable settlement row
 * into a PERSISTED reconciliation exception (durable {@code saveException} call),
 * not a silent drop or warn-only. Uses the real {@link StripeCsvParser} so the
 * full parse → persist seam is exercised, with a mocked repository to assert the
 * durable write.
 *
 * <p>FIX 2: parse-failure exceptions are persisted via a dedicated
 * {@link ParseFailureRecorder} bean running in its own {@code REQUIRES_NEW}
 * transaction, so a downstream rollback cannot erase them. Most tests here wire a
 * REAL recorder over the mocked repository (so {@code saveException} assertions
 * still exercise the durable write), and one test swaps in a mock recorder to
 * prove the service DELEGATES to it.</p>
 */
class SettlementIngestionServiceTest {

    private final ReconciliationRepository repository = mock(ReconciliationRepository.class);
    private final ParseFailureRecorder parseFailureRecorder = new ParseFailureRecorder(repository);
    private final SettlementParserPort stripeParser = new StripeCsvParser();
    private final SettlementFilePort fileSource = mock(SettlementFilePort.class);

    private SettlementIngestionService service() {
        when(fileSource.source()).thenReturn("local");
        return new SettlementIngestionService(
                repository, parseFailureRecorder, List.of(stripeParser), List.of(fileSource));
    }

    private SettlementIngestionService serviceWith(ParseFailureRecorder recorder) {
        when(fileSource.source()).thenReturn("local");
        return new SettlementIngestionService(
                repository, recorder, List.of(stripeParser), List.of(fileSource));
    }

    private InputStream csv(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void malformedRow_persistsParseErrorException_notSilentDrop() {
        String content = """
                id,amount,currency,fee,net,created
                txn_bad,abc,usd,10,490,2026-01-02
                """;
        SettlementIngestionService svc = service();

        ReconciliationRun run = svc.ingestFromStream("tenant-1", "stripe", "stripe.csv", csv(content));

        // No good records → none saved as settlement records, but the bad row is
        // NOT dropped: exactly one durable exception is persisted.
        ArgumentCaptor<ReconciliationException> captor = ArgumentCaptor.forClass(ReconciliationException.class);
        verify(repository, times(1)).saveException(captor.capture());

        ReconciliationException ex = captor.getValue();
        assertThat(ex.getExceptionType()).isEqualTo(MatchResult.ExceptionType.PARSE_ERROR);
        assertThat(ex.getStatus()).isEqualTo(ReconciliationException.ExceptionStatus.OPEN);
        // FK column is nullable and there is no settlement record for a parse failure.
        assertThat(ex.getSettlementRecordId()).isNull();
        assertThat(ex.getReconciliationRunId()).isEqualTo(run.getId());
        assertThat(ex.getTenantId()).isEqualTo("tenant-1");
        // Description must carry the line number and the raw row for investigation.
        assertThat(ex.getDescription()).contains("line 2").contains("txn_bad");
    }

    @Test
    void parseErrorType_roundTripsThroughEntityMapper() {
        // Guards the risk that ExceptionType.valueOf("PARSE_ERROR") throws on
        // read-back: the name must be a real enum constant.
        assertThat(MatchResult.ExceptionType.valueOf("PARSE_ERROR"))
                .isEqualTo(MatchResult.ExceptionType.PARSE_ERROR);
        assertThat(MatchResult.ExceptionType.PARSE_ERROR.name())
                .isEqualTo("PARSE_ERROR")
                .hasSizeLessThanOrEqualTo(32); // fits VARCHAR(32) exception_type
    }

    @Test
    void mixedFile_persistsGoodRecords_andOneExceptionPerBadRow() {
        String content = """
                id,amount,currency,fee,net,created,description
                txn_1,10000,usd,290,9710,2026-03-14,"Refund, partial"
                txn_bad1,abc,usd,10,490,2026-01-02,bad amount
                txn_2,500,eur,5,495,2026-01-03,ok
                txn_bad2,1000,usd,10,990,2026-13-40,bad date
                """;
        SettlementIngestionService svc = service();

        svc.ingestFromStream("tenant-1", "stripe", "stripe.csv", csv(content));

        // 2 good records persisted in one batch.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SettlementRecord>> recordsCaptor = ArgumentCaptor.forClass(List.class);
        verify(repository, times(1)).saveAllSettlementRecords(recordsCaptor.capture());
        assertThat(recordsCaptor.getValue()).hasSize(2);
        assertThat(recordsCaptor.getValue()).extracting(SettlementRecord::getExternalId)
                .containsExactlyInAnyOrder("txn_1", "txn_2");

        // 2 bad rows → exactly 2 PARSE_ERROR exceptions persisted. Nothing vanishes:
        // 2 records + 2 exceptions == 4 data rows.
        ArgumentCaptor<ReconciliationException> exCaptor = ArgumentCaptor.forClass(ReconciliationException.class);
        verify(repository, times(2)).saveException(exCaptor.capture());
        assertThat(exCaptor.getAllValues())
                .allMatch(e -> e.getExceptionType() == MatchResult.ExceptionType.PARSE_ERROR)
                .allMatch(e -> e.getStatus() == ReconciliationException.ExceptionStatus.OPEN)
                .allMatch(e -> e.getSettlementRecordId() == null);
    }

    @Test
    void wellFormedFile_persistsNoExceptions() {
        String content = """
                id,amount,currency,fee,net,created
                txn_1,500,usd,10,490,2026-01-02
                txn_2,750,eur,15,735,2026-01-03
                """;
        SettlementIngestionService svc = service();

        svc.ingestFromStream("tenant-1", "stripe", "stripe.csv", csv(content));

        verify(repository, never()).saveException(any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SettlementRecord>> recordsCaptor = ArgumentCaptor.forClass(List.class);
        verify(repository, times(1)).saveAllSettlementRecords(recordsCaptor.capture());
        assertThat(recordsCaptor.getValue()).hasSize(2);
    }

    @Test
    void ingestFromSource_alsoPersistsParseErrors() {
        String content = """
                id,amount,currency,fee,net,created
                txn_bad,abc,usd,10,490,2026-01-02
                """;
        SettlementIngestionService svc = service();
        when(fileSource.fetch(any())).thenReturn(csv(content));

        svc.ingest("tenant-1", "stripe", "local", "/drop/stripe.csv");

        verify(repository, times(1)).saveException(any());
    }

    // ---- FIX 1: lone/unterminated quote must not abort the whole file ----

    @Test
    void loneQuoteRow_stillPersistsGoodRecords_andParseError_ingestDoesNotThrow() {
        // A lone/unterminated quote makes the CSV tokenizer throw mid-stream. The
        // good leading row must STILL be persisted, the bad row must STILL become a
        // durable PARSE_ERROR exception, and ingest must NOT throw.
        String content = "id,amount,currency,fee,net,created,description\n"
                + "txn_good,500,usd,10,490,2026-01-02,ok\n"
                + "txn_bad,200,usd,5,195,2026-01-03,\"unterminated\n";
        SettlementIngestionService svc = service();

        assertThatCode(() -> svc.ingestFromStream("tenant-1", "stripe", "stripe.csv", csv(content)))
                .doesNotThrowAnyException();

        // The good leading row is persisted (proves no pre-abort discard).
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SettlementRecord>> recordsCaptor = ArgumentCaptor.forClass(List.class);
        verify(repository, times(1)).saveAllSettlementRecords(recordsCaptor.capture());
        assertThat(recordsCaptor.getValue()).extracting(SettlementRecord::getExternalId)
                .contains("txn_good");

        // The tokenizer failure is recorded as a durable PARSE_ERROR exception.
        ArgumentCaptor<ReconciliationException> exCaptor = ArgumentCaptor.forClass(ReconciliationException.class);
        verify(repository, times(1)).saveException(exCaptor.capture());
        assertThat(exCaptor.getValue().getExceptionType()).isEqualTo(MatchResult.ExceptionType.PARSE_ERROR);
    }

    // ---- FIX 2: failures are persisted via the REQUIRES_NEW recorder bean ----

    @Test
    void parseFailures_areDelegatedToParseFailureRecorder() {
        // Prove the service DELEGATES failure persistence to the recorder bean
        // (so the recorder's REQUIRES_NEW boundary is honored, not a self-invoke).
        ParseFailureRecorder recorder = mock(ParseFailureRecorder.class);
        SettlementIngestionService svc = serviceWith(recorder);
        String content = """
                id,amount,currency,fee,net,created
                txn_1,500,usd,10,490,2026-01-02
                txn_bad,abc,usd,10,490,2026-01-02
                """;

        ReconciliationRun run = svc.ingestFromStream("tenant-1", "stripe", "stripe.csv", csv(content));

        // The recorder is invoked with the run and exactly the parse failures —
        // the service never calls repository.saveException itself.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ParseFailure>> failuresCaptor = ArgumentCaptor.forClass(List.class);
        verify(recorder, times(1)).record(eq(run), failuresCaptor.capture());
        assertThat(failuresCaptor.getValue()).hasSize(1);
        assertThat(failuresCaptor.getValue().get(0).rawLine()).contains("txn_bad");
        // Delegation, not self-invocation: the service did not persist directly.
        verify(repository, never()).saveException(any());
    }

    @Test
    void parseFailureRecorder_record_isAnnotatedRequiresNew() throws NoSuchMethodException {
        // The whole point of FIX 2: the recorder commits in its OWN transaction so
        // a downstream rollback cannot erase persisted PARSE_ERROR rows. A full
        // Testcontainers IT (drive runFromUpload, stub matching to throw, assert the
        // row is still queryable via findExceptionsByRunId) is NOT feasible in this
        // module — `reconciliation` is a plain `java` module with no Spring Boot
        // plugin and no Testcontainers/Postgres test infra (that lives only in the
        // `app` module). So we assert the strongest reachable proof: the persistence
        // method carries @Transactional(propagation = REQUIRES_NEW). RESIDUAL: the
        // runtime rollback-isolation is exercised by the app-module integration
        // suite, not here.
        Method record = ParseFailureRecorder.class.getMethod(
                "record", ReconciliationRun.class, List.class);
        org.springframework.transaction.annotation.Transactional tx =
                record.getAnnotation(org.springframework.transaction.annotation.Transactional.class);
        assertThat(tx).as("ParseFailureRecorder.record must be @Transactional").isNotNull();
        assertThat(tx.propagation())
                .as("must run in a SEPARATE committed transaction (survives downstream rollback)")
                .isEqualTo(org.springframework.transaction.annotation.Propagation.REQUIRES_NEW);
    }
}
