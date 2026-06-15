package io.nexuspay.app.redteam;

import io.nexuspay.app.IntegrationTestBase;
import io.nexuspay.app.config.TestSecurityConfig;
import io.nexuspay.gateway.application.port.in.TokenizePaymentMethodUseCase;
import io.nexuspay.gateway.application.port.in.TokenizePaymentMethodUseCase.TokenizeCommand;
import io.nexuspay.gateway.application.port.out.PaymentSessionRepository;
import io.nexuspay.gateway.domain.PaymentSession;
import io.nexuspay.gateway.domain.PaymentToken;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * RED-TEAM (report-only, {@code @Tag("redteam")}): full PAN recoverable at rest in
 * the gateway SDK tokenize path (audit B-004 / "SEC-04").
 *
 * <p><strong>Attack:</strong> drive the SDK {@code tokenize} flow with a KNOWN PAN
 * (the checkout frame base64-encodes the raw card number into {@code token_data}),
 * then read the raw {@code payment_tokens.token_data} bytes straight from the
 * database (modelling a stolen DB dump / read-only SQLi). A SECURE tokenize path
 * routes the card through the encrypted vault (AES-256-GCM behind a KMS key) and
 * sets {@code encryption_key_id}, so the cleartext PAN is NOT recoverable from the
 * stored bytes as ASCII or by base64-decoding them.</p>
 *
 * <p><strong>This is the REAL audit finding, not the vault path.</strong> An
 * earlier version of this test attacked {@code vaulted_cards.encrypted_pan} — but
 * the vault already uses real AES-256-GCM ({@code AesGcmEncryptionAdapter}), so
 * that assertion PASSED on main and was vacuous (false assurance). The genuine
 * B-004 hole is a DIFFERENT path: {@code CheckoutController.tokenize}
 * ({@code request.token_data().getBytes(UTF_8)}) hands the raw, base64-PAN bytes to
 * {@code TokenizationService}, which persists them verbatim into
 * {@code payment_tokens.token_data} with a null {@code encryption_key_id} — no
 * encryption at all. We drive the use case directly (mirroring how the SDK frame
 * supplies {@code token_data}) and assert on the stored column, so this FAILS on
 * current main (the base64 PAN IS the stored content).</p>
 *
 * <p><strong>Why this FAILS on current main (excluded + report-only):</strong>
 * {@code token_data} is the unencrypted base64 of the PAN, so {@code base64Decode}
 * of the stored bytes yields the full PAN and the literal base64(PAN) IS the stored
 * value. When the SEC fix lands (route SDK tokenize through the encrypted vault and
 * set {@code encryption_key_id}; reject null-key tokens), drop {@code @Tag("redteam")}
 * to gate it.</p>
 */
@Tag("redteam")
@Import(TestSecurityConfig.class)
@DisplayName("RED-TEAM: full PAN recoverable at rest in payment_tokens.token_data (B-004/SEC-04)")
class PanPersistenceRedteamTest extends IntegrationTestBase {

    // A canonical test PAN (Visa test number). Must NOT be recoverable from storage.
    private static final String KNOWN_PAN = "4111111111111111";
    private static final String TENANT = "default";

    @Autowired
    private TokenizePaymentMethodUseCase tokenizeUseCase;

    @Autowired
    private PaymentSessionRepository sessionRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void requireDocker() {
        Assumptions.assumeTrue(DOCKER_AVAILABLE,
                "Docker unavailable — PAN-persistence red-team self-skips (Testcontainers required)");
    }

    @Test
    @DisplayName("the full PAN is NOT recoverable from payment_tokens.token_data after a tokenize")
    void fullPan_isNotRecoverableInTokenData() {
        // The checkout SDK frame base64-encodes the raw PAN and posts it as token_data
        // (CheckoutController turns that string into UTF-8 bytes verbatim). Replicate
        // exactly that on-the-wire shape.
        String panBase64 = Base64.getEncoder()
                .encodeToString(KNOWN_PAN.getBytes(StandardCharsets.US_ASCII));
        byte[] sdkTokenData = panBase64.getBytes(StandardCharsets.UTF_8);

        // Seed an OPEN, non-expired session so tokenize passes its session/rate-limit gate
        // and reaches the persistence step (the FK payment_tokens.session_id requires it).
        String sessionId = "ps_redteam_pan_" + UUID.randomUUID();
        Instant now = Instant.now();
        sessionRepository.save(new PaymentSession(
                sessionId, TENANT, null, "cs_redteam_" + UUID.randomUUID(),
                5000L, "USD", PaymentSession.STATUS_OPEN, "cus_redteam",
                List.of("card"), "https://ok", "https://cancel",
                Map.of(), Map.of(),
                0, now.plus(15, ChronoUnit.MINUTES), now, now));

        // Drive the SDK tokenize path (the B-004 vector).
        PaymentToken token = tokenizeUseCase.tokenize(new TokenizeCommand(
                sessionId, TENANT, PaymentToken.TYPE_CARD, sdkTokenData,
                KNOWN_PAN.substring(KNOWN_PAN.length() - 4), "visa", 12, 2030));

        // Read the raw stored bytes back for the just-created token.
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT token_data, encryption_key_id FROM payment_tokens WHERE id = ?",
                token.getId());

        org.assertj.core.api.Assertions.assertThat(rows)
                .as("a payment_tokens row must exist for the tokenized card")
                .isNotEmpty();

        byte[] stored = (byte[]) rows.get(0).get("token_data");
        org.assertj.core.api.Assertions.assertThat(stored)
                .as("token_data must not be null/empty").isNotNull().isNotEmpty();

        String storedAscii = new String(stored, StandardCharsets.ISO_8859_1);

        // (1) The PAN must not appear as raw bytes / ASCII in the stored column.
        org.assertj.core.api.Assertions.assertThat(storedAscii)
                .as("token_data must not contain the cleartext PAN")
                .doesNotContain(KNOWN_PAN);

        // (2) The literal base64(PAN) must not be the stored content — the exact B-004
        //     failure mode (the SDK's base64-of-plaintext persisted unencrypted).
        org.assertj.core.api.Assertions.assertThat(storedAscii)
                .as("token_data must not be base64(PAN) — route SDK tokenize through the encrypted vault")
                .doesNotContain(panBase64);

        // (3) And base64-decoding the stored value must not recover the cleartext PAN.
        String recovered = tryBase64Decode(storedAscii);
        if (recovered != null) {
            org.assertj.core.api.Assertions.assertThat(recovered)
                    .as("base64-decoding token_data must NOT yield the cleartext PAN")
                    .doesNotContain(KNOWN_PAN);
        }
    }

    /** Best-effort base64 decode; returns null if the bytes are not valid base64. */
    private static String tryBase64Decode(String candidate) {
        try {
            return new String(Base64.getDecoder().decode(candidate.trim()), StandardCharsets.ISO_8859_1);
        } catch (IllegalArgumentException notBase64) {
            return null;
        }
    }
}
