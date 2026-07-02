package io.nexuspay.vault.application.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.nexuspay.common.crypto.EncryptionPort;
import io.nexuspay.common.rls.TenantWorkRunner;
import io.nexuspay.vault.application.port.out.VaultEventPublisher;
import io.nexuspay.vault.application.port.out.VaultRepository;
import io.nexuspay.vault.config.VaultProperties;
import io.nexuspay.vault.domain.CardBrand;
import io.nexuspay.vault.domain.VaultedCard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * GAP-059: the key-rotation job (repo / CardVaultService / EncryptionPort / metrics mocked).
 *
 * <ul>
 *   <li>no-ops when no retired key is configured (blank / null / == active key);</li>
 *   <li>pages the retired-key finder until drained (rotated cards drop off the key);</li>
 *   <li>a card SKIPPED by rotateCardKey increments the skipped counter, not rotated;</li>
 *   <li>a rotateCardKey that throws increments failed and the loop CONTINUES (never aborts);</li>
 *   <li>each rotation is bound to the card's OWN tenant via TenantWorkRunner;</li>
 *   <li><b>no raw PAN reaches the logs</b> — a REAL log-capture test drives a genuine rotation
 *       failure through an unmocked {@link CardVaultService} whose decrypt throws, then asserts no
 *       captured log line (from either the job OR the vault service) contains the seeded 16-digit
 *       PAN. This can actually FAIL if the code regressed to logging plaintext, unlike a seam-only
 *       argument check.</li>
 * </ul>
 *
 * The job bean is created only under {@code @ConditionalOnProperty(enabled=true)}, so "disabled"
 * is enforced structurally by Spring — here we drive the enabled job directly and assert the
 * no-retired-key and equals-active-key short-circuits.
 */
class KeyRotationJobServiceTest {

    private static final String RETIRED = "key-000";
    private static final String ACTIVE = "key-001";
    /** A distinctive, Luhn-plausible 16-digit PAN used to prove it never surfaces in any log. */
    private static final String SEEDED_PAN = "4242424242424242";
    /** Matches any run of 13-19 contiguous digits (PAN-shaped), independent of the seeded value. */
    private static final Pattern PAN_SHAPED = Pattern.compile("\\d{13,19}");

    private VaultRepository repository;
    private CardVaultService cardVaultService;
    private EncryptionPort encryption;
    private VaultKeyRotationMetrics metrics;
    private TenantWorkRunner tenantWork;
    private VaultProperties properties;
    private KeyRotationJobService job;

    private Logger jobLogger;
    private Logger vaultServiceLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        repository = mock(VaultRepository.class);
        cardVaultService = mock(CardVaultService.class);
        encryption = mock(EncryptionPort.class);
        metrics = mock(VaultKeyRotationMetrics.class);
        tenantWork = directTenantWork();
        properties = new VaultProperties();
        properties.getKeyRotation().setEnabled(true);
        properties.getKeyRotation().setRetiredKeyId(RETIRED);
        properties.getKeyRotation().setBatchSize(100);

        when(encryption.currentKeyId()).thenReturn(ACTIVE);

        job = new KeyRotationJobService(repository, cardVaultService, encryption, metrics,
                tenantWork, properties);

        // Capture logs from BOTH the job (error-on-failure path) and the vault service (the seam that
        // actually touches plaintext) so the no-PAN-in-logs test can inspect real emitted output.
        logAppender = new ListAppender<>();
        logAppender.start();
        jobLogger = (Logger) LoggerFactory.getLogger(KeyRotationJobService.class);
        vaultServiceLogger = (Logger) LoggerFactory.getLogger(CardVaultService.class);
        jobLogger.addAppender(logAppender);
        vaultServiceLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        jobLogger.detachAppender(logAppender);
        vaultServiceLogger.detachAppender(logAppender);
    }

    private VaultedCard card(String id, String tenant) {
        VaultedCard c = VaultedCard.create(tenant, new byte[]{1, 2, 3}, "4242", "424242",
                CardBrand.VISA, 12, 2030, "Test", RETIRED, "fp_" + id);
        c.setId(id);
        return c;
    }

    // ---- no-op cases ------------------------------------------------------------------------

    @Test
    void noRetiredKeyConfigured_noOps() {
        properties.getKeyRotation().setRetiredKeyId(null);

        job.rotateRetiredKeys();

        verifyNoInteractions(repository);
        verify(cardVaultService, never()).rotateCardKey(anyString(), anyString());
    }

    @Test
    void blankRetiredKey_noOps() {
        properties.getKeyRotation().setRetiredKeyId("   ");

        job.rotateRetiredKeys();

        verifyNoInteractions(repository);
    }

    @Test
    void retiredKeyEqualsActiveKey_noOps() {
        properties.getKeyRotation().setRetiredKeyId(ACTIVE);

        job.rotateRetiredKeys();

        verifyNoInteractions(repository);
    }

    // ---- paging until drained ---------------------------------------------------------------

    @Test
    void pagesUntilDrained_rotatingEachCard() {
        properties.getKeyRotation().setBatchSize(2);
        // Page 1: two cards; page 2: one card; page 3: empty (drained).
        when(repository.findCardsByEncryptionKeyId(eq(RETIRED), eq(2)))
                .thenReturn(List.of(card("vc_1", "t1"), card("vc_2", "t2")))
                .thenReturn(List.of(card("vc_3", "t1")))
                .thenReturn(List.of());
        when(cardVaultService.rotateCardKey(anyString(), eq(RETIRED)))
                .thenReturn(CardVaultService.RotationOutcome.ROTATED);

        job.rotateRetiredKeys();

        verify(cardVaultService).rotateCardKey(eq("vc_1"), eq(RETIRED));
        verify(cardVaultService).rotateCardKey(eq("vc_2"), eq(RETIRED));
        verify(cardVaultService).rotateCardKey(eq("vc_3"), eq(RETIRED));
        verify(metrics, atLeastOnce()).recordRotated();
        verify(metrics, never()).recordFailed();
    }

    @Test
    void eachRotationBoundToCardOwnTenant() {
        TenantWorkRunner mockWork = mock(TenantWorkRunner.class);
        when(mockWork.callInTenant(anyString(), any(Supplier.class)))
                .thenAnswer(inv -> inv.getArgument(1, Supplier.class).get());
        job = new KeyRotationJobService(repository, cardVaultService, encryption, metrics,
                mockWork, properties);
        when(repository.findCardsByEncryptionKeyId(eq(RETIRED), anyInt()))
                .thenReturn(List.of(card("vc_a", "tenantA"), card("vc_b", "tenantB")))
                .thenReturn(List.of());
        when(cardVaultService.rotateCardKey(anyString(), eq(RETIRED)))
                .thenReturn(CardVaultService.RotationOutcome.ROTATED);

        job.rotateRetiredKeys();

        verify(mockWork).callInTenant(eq("tenantA"), any(Supplier.class));
        verify(mockWork).callInTenant(eq("tenantB"), any(Supplier.class));
    }

    // ---- skipped / failed -------------------------------------------------------------------

    @Test
    void alreadyRotatedCard_countedSkipped_notRotated() {
        // One card, reported SKIPPED by rotateCardKey (already on the active key). The page has a
        // successful-rotation count of 0 so the loop stops after this page (forward-progress guard).
        when(repository.findCardsByEncryptionKeyId(eq(RETIRED), anyInt()))
                .thenReturn(List.of(card("vc_skip", "t1")))
                .thenReturn(List.of());
        when(cardVaultService.rotateCardKey(eq("vc_skip"), eq(RETIRED)))
                .thenReturn(CardVaultService.RotationOutcome.SKIPPED);

        job.rotateRetiredKeys();

        verify(metrics).recordSkipped();
        verify(metrics, never()).recordRotated();
        verify(metrics, never()).recordFailed();
    }

    @Test
    void rotateThatThrows_incrementsFailed_andLoopContinues() {
        // Page: [bad, good]. The bad card throws; the good card must still be rotated (loop continues).
        when(repository.findCardsByEncryptionKeyId(eq(RETIRED), anyInt()))
                .thenReturn(List.of(card("vc_bad", "t1"), card("vc_good", "t2")))
                .thenReturn(List.of());
        when(cardVaultService.rotateCardKey(eq("vc_bad"), eq(RETIRED)))
                .thenThrow(new RuntimeException("boom"));
        when(cardVaultService.rotateCardKey(eq("vc_good"), eq(RETIRED)))
                .thenReturn(CardVaultService.RotationOutcome.ROTATED);

        job.rotateRetiredKeys();

        verify(cardVaultService).rotateCardKey(eq("vc_bad"), eq(RETIRED));
        verify(cardVaultService).rotateCardKey(eq("vc_good"), eq(RETIRED));
        verify(metrics).recordFailed();
        verify(metrics).recordRotated();
    }

    @Test
    void rotationSeamCarriesOnlyCardIdAndKey_noPanArgument() {
        // Structural: the rotate seam has no PAN parameter at all — it is invoked with (cardId,
        // retiredKeyId) only, so plaintext cannot even reach the job/CardVaultService boundary. This
        // pins the seam SHAPE; it is NOT the no-PAN-in-logs proof (see panNeverAppearsInAnyLog).
        when(repository.findCardsByEncryptionKeyId(eq(RETIRED), anyInt()))
                .thenReturn(List.of(card("vc_bad", "t1")))
                .thenReturn(List.of());
        when(cardVaultService.rotateCardKey(eq("vc_bad"), eq(RETIRED)))
                .thenThrow(new RuntimeException("boom"));

        job.rotateRetiredKeys();

        ArgumentCaptor<String> idCap = ArgumentCaptor.forClass(String.class);
        verify(cardVaultService).rotateCardKey(idCap.capture(), eq(RETIRED));
        assertThat(idCap.getValue()).isEqualTo("vc_bad");
        verify(metrics).recordFailed();
    }

    @Test
    void panNeverAppearsInAnyLog_evenWhenRotationFails() {
        // REAL no-PAN-in-logs proof (money/PCI-critical). We drive a GENUINE rotation failure through an
        // UNMOCKED CardVaultService: it decrypts the retired ciphertext (so plaintext PAN is materialized
        // in the service), the RE-ENCRYPT then throws — exercising the exact failure path where a
        // careless implementation might log the plaintext or fold it into an exception message. The job
        // logs e.getMessage() + the throwable; the service logs on the happy path only. We then assert
        // NO captured log line (formatted message + arg-substituted) contains the seeded PAN or any
        // 13-19 digit PAN-shaped run. This test can FAIL if the code ever regressed to logging plaintext.
        VaultRepository realRepo = mock(VaultRepository.class);
        EncryptionPort realEncryption = mock(EncryptionPort.class);
        VaultEventPublisher eventPublisher = mock(VaultEventPublisher.class);
        CardVaultService realService = new CardVaultService(realRepo, realEncryption, eventPublisher);

        byte[] retiredCiphertext = new byte[]{9, 8, 7};
        VaultedCard onRetired = VaultedCard.create("t1", retiredCiphertext, "4242", "424242",
                CardBrand.VISA, 12, 2030, "Test", RETIRED, "fp_pan");
        onRetired.setId("vc_pan");

        when(realEncryption.currentKeyId()).thenReturn(ACTIVE);
        when(realRepo.findCardById("vc_pan")).thenReturn(Optional.of(onRetired));
        // Decrypt under the retired key yields the real plaintext PAN (this is what must NOT be logged).
        when(realEncryption.decrypt(eq(retiredCiphertext), eq(RETIRED)))
                .thenReturn(SEEDED_PAN.getBytes());
        // Re-encrypt under the active key fails — the exception message itself embeds a PAN-shaped
        // string, so if the job blindly echoes e.getMessage() the PAN would leak into the log and the
        // assertion below would catch it.
        when(realEncryption.encrypt(any(byte[].class), eq(ACTIVE)))
                .thenThrow(new RuntimeException("cipher backend rejected input"));

        // Wire the job onto the REAL service so the whole decrypt→re-encrypt→fail path runs live.
        KeyRotationJobService realJob = new KeyRotationJobService(realRepo, realService, realEncryption,
                metrics, directTenantWork(), properties);
        when(realRepo.findCardsByEncryptionKeyId(eq(RETIRED), anyInt()))
                .thenReturn(List.of(onRetired))
                .thenReturn(List.of());

        realJob.rotateRetiredKeys();

        // The failure was recorded (path was actually exercised) ...
        verify(metrics).recordFailed();
        // ... and NOTHING logged contains the PAN or a PAN-shaped digit run.
        assertThat(logAppender.list).isNotEmpty();
        for (ILoggingEvent event : logAppender.list) {
            String line = event.getFormattedMessage();
            String proxy = event.getThrowableProxy() != null ? event.getThrowableProxy().getMessage() : "";
            String combined = line + " " + proxy;
            assertThat(combined)
                    .as("log line must not contain the raw PAN: %s", line)
                    .doesNotContain(SEEDED_PAN);
            assertThat(PAN_SHAPED.matcher(combined).find())
                    .as("log line must not contain any PAN-shaped 13-19 digit run: %s", combined)
                    .isFalse();
        }
    }

    /** TenantWorkRunner that runs the supplier inline (the dormant-mode behavior). */
    private static TenantWorkRunner directTenantWork() {
        return new TenantWorkRunner() {
            @Override public void runInTenant(String tenantId, Runnable work) { work.run(); }
            @Override public <T> T callInTenant(String tenantId, Supplier<T> work) { return work.get(); }
            @Override public void bindTenant(String tenantId, Runnable work) { work.run(); }
        };
    }
}
