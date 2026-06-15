package io.nexuspay.fraud.application.service;

import io.nexuspay.fraud.application.dto.PaymentContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Computes a KEYED, one-way request fingerprint for a {@link PaymentContext} so the idempotent
 * dedup-hit path in {@code FraudAssessmentService} can verify that a retried Idempotency-Key
 * actually describes the SAME charge (amount/currency/customer/card-token) before serving the prior
 * fraud decision (B-029-hardening / SHOULD_FIX C).
 *
 * <h2>Why KEYED HMAC, not a plain hash</h2>
 * The fingerprinted tuple has catastrophically low entropy ({@code amountMinorUnits} is a small
 * integer, {@code currency} is one of ~150 ISO codes, {@code customerId} is a short id, and
 * {@code cardHash}, while a token, is also low-cardinality per merchant). A plain SHA-256 over this
 * tuple would be trivially brute-forceable / rainbow-tableable: anyone who reads the
 * {@code request_fingerprint} column (DB dump, backup leak, SQLi, insider) could enumerate amounts
 * and customer ids and recover the cleartext request for every assessment. A keyed HMAC with a
 * secret the attacker does not possess makes the column non-invertible even with full
 * plaintext-space enumeration. This mirrors the L-009 vault PAN-fingerprint reasoning.
 *
 * <h2>Key derivation &amp; domain separation</h2>
 * The HMAC key is derived from the shared {@code nexuspay.vault.encryption.master-key} with the
 * domain-separation label {@value #DOMAIN_LABEL} (distinct from vault's {@code "fingerprint"}), so
 * the fraud request key is cryptographically independent of the vault PAN-fingerprint key even
 * though both descend from the same master key:
 * {@code fingerprintKey = SHA-256( base64decode(masterKey) || UTF8("fraud-request-fingerprint") )}.
 *
 * <h2>Fail-closed</h2>
 * {@link #fingerprint(PaymentContext)} wraps the {@link Mac}/{@link MessageDigest} work in a
 * try/catch and on ANY failure throws {@link FingerprintUnavailableException} — it NEVER returns
 * {@code null} and NEVER returns a sentinel that could compare-equal to a stored value. The caller
 * fails CLOSED on that exception.
 *
 * @since B-029-hardening
 */
@Component
public class RequestFingerprinter {

    /** Domain-separation label — independent from vault's {@code "fingerprint"} key. */
    static final String DOMAIN_LABEL = "fraud-request-fingerprint";

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    // --- Canonical encoding constants (a length-prefixed, type-tagged, null-marked serialization) ---
    /**
     * Format version. Bumping this yields a forward-compatible v2 that never collides with v1.
     * <p>B-029-hardening NIT 1: bumped 0x01 -&gt; 0x02 when {@code tenantId} was folded into the
     * canonical tuple as a self-binding, length-prefixed field (TAG_TENANT). This is safe because the
     * {@code request_fingerprint} column is brand-new — no production fingerprints exist yet, so there
     * is no back-compat concern with the format change.
     */
    private static final byte TAG_VERSION = 0x02;
    /** Tenant binds the fingerprint to its tenant so it can never be honored cross-context. */
    private static final byte TAG_TENANT = 0x14;
    private static final byte TAG_AMOUNT = 0x10;
    private static final byte TAG_CURRENCY = 0x11;
    private static final byte TAG_CUSTOMER = 0x12;
    private static final byte TAG_CARD = 0x13;
    /** Marker for an absent (null) field — distinguishes {@code null} from the empty string. */
    private static final byte MARKER_NULL = 0x00;
    /** Marker for a present field; followed by an 8-byte BE length and that many UTF-8 bytes. */
    private static final byte MARKER_PRESENT = 0x01;

    private final String masterKeyBase64;

    public RequestFingerprinter(
            @Value("${nexuspay.vault.encryption.master-key}") String masterKeyBase64) {
        this.masterKeyBase64 = masterKeyBase64;
    }

    /**
     * Computes the keyed HMAC-SHA256 fingerprint (64 lowercase hex chars) of the canonical request
     * tuple {@code (amountMinorUnits, currency, customerId, cardHash)}.
     *
     * @throws FingerprintUnavailableException if the key is unusable or the HMAC/digest fails —
     *         the caller treats this as fail-CLOSED (propagate on write; re-assess on compare).
     */
    public String fingerprint(PaymentContext context) {
        try {
            byte[] canonical = canonicalize(
                    context.tenantId(),
                    context.amountMinorUnits(),
                    context.currency(),
                    context.customerId(),
                    context.cardHash());
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(fingerprintKey(), HMAC_ALGORITHM));
            byte[] digest = mac.doFinal(canonical);
            return HexFormat.of().formatHex(digest); // 32 bytes -> 64 lowercase hex chars
        } catch (Exception e) {
            // FAIL CLOSED: never null, never a sentinel that could compare-equal to a stored value.
            throw new FingerprintUnavailableException(
                    "Unable to compute fraud request fingerprint", e);
        }
    }

    /** Derives the domain-separated HMAC key from the shared master key (mirrors L-009 vault). */
    private byte[] fingerprintKey() throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(Base64.getDecoder().decode(masterKeyBase64)); // throws on non-base64 -> fail closed
        digest.update(DOMAIN_LABEL.getBytes(StandardCharsets.UTF_8));
        return digest.digest();
    }

    /**
     * Builds the canonical, INJECTIVE byte form of the request tuple. Each field is emitted as a
     * 1-byte type tag, then either a NULL marker, or a PRESENT marker + 8-byte big-endian length +
     * exactly that many UTF-8 bytes. Because every present field is self-delimiting by its explicit
     * length prefix, no in-band delimiter exists to inject and no value's bytes can be misread as a
     * field boundary (defeats the {@code "1|US|a"} vs {@code "1|USa|"} pipe-join forgery). The
     * distinct NULL marker makes {@code null} != {@code ""}. The encoding is uniquely decodable,
     * hence injective.
     *
     * <p>B-029-hardening NIT 1: {@code tenantId} is written FIRST (TAG_TENANT, before amount) as a
     * length-prefixed/null-marked field exactly like the others, making the fingerprint SELF-BINDING
     * to its tenant. Even if a future refactor compared fingerprints OUTSIDE the tenant-scoped lookup,
     * a tenant-A request could never compare-equal to a tenant-B request. This is pure
     * defense-in-depth: it does not change the dedup-hit outcome, because {@code tenantId} is constant
     * across a tenant-scoped hit.
     */
    static byte[] canonicalize(String tenantId, long amountMinorUnits, String currency,
                               String customerId, String cardHash) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(TAG_VERSION);
        // tenant FIRST: self-binds the fingerprint to its tenant (NIT 1, defense-in-depth).
        writeField(out, TAG_TENANT, tenantId);
        // amount: long -> base-10 ASCII via Long.toString, treated as a present string field.
        writeField(out, TAG_AMOUNT, Long.toString(amountMinorUnits));
        writeField(out, TAG_CURRENCY, currency);
        writeField(out, TAG_CUSTOMER, customerId);
        writeField(out, TAG_CARD, cardHash);
        return out.toByteArray();
    }

    private static void writeField(ByteArrayOutputStream out, byte tag, String value) {
        out.write(tag);
        if (value == null) {
            out.write(MARKER_NULL);
            return;
        }
        out.write(MARKER_PRESENT);
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeLongBE(out, bytes.length);
        out.write(bytes, 0, bytes.length);
    }

    /** Writes a value as a fixed 8-byte big-endian long (the field length prefix). */
    private static void writeLongBE(ByteArrayOutputStream out, long value) {
        for (int shift = 56; shift >= 0; shift -= 8) {
            out.write((int) (value >>> shift) & 0xFF);
        }
    }
}
