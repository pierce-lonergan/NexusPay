package io.nexuspay.payment.adapter.in.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * GAP-015 (reliability): operator-initiated reprocess of an inbound HyperSwitch webhook that FAILED
 * after HMAC verification (e.g. a post-persist outbox write threw). Without this, recovery needs manual
 * SQL — the row is stranded FAILED and the merchant silently loses the capture/refund/dispute event.
 *
 * <h3>Auth + rate-limit — mounted on the {@code /v1/admin} admin surface.</h3>
 * Reprocess is an OPERATOR action with NO HyperSwitch HMAC to verify, and it drives a privileged,
 * ledger-adjacent outbox re-emit — so it MUST NOT sit on the {@code /internal/webhooks/**}
 * {@code permitAll()} PSP-delivery tree. It is mounted under {@code /v1/admin/webhooks/**} — the SAME
 * admin surface as {@code DeadLetterController} ({@code /v1/admin/dead-letters}) — which (a) falls through
 * to {@code .anyRequest().authenticated()} in {@code iam SecurityConfig}, (b) is gated with
 * {@code @PreAuthorize("hasRole('admin')")} (the exact admin idiom {@code DeadLetterController}/
 * {@code ApprovalController} use; {@code SecurityConfig} maps a Keycloak {@code admin} realm role to
 * {@code ROLE_admin}), AND (c) is covered by the per-principal {@code RateLimitFilter} (@Order 10), which
 * {@code shouldNotFilter}s {@code /internal/} but NOT {@code /v1/**}. Mounting under {@code /internal/admin}
 * (the earlier choice) left this privileged endpoint with ZERO rate limiting — the gateway
 * {@code RateLimitFilter} skips any {@code /internal/} path and the {@code InternalWebhookRateLimitFilter}
 * only matches the literal {@code /internal/webhooks} prefix — so a leaked/abused admin token could drive
 * unbounded outbox re-emits. The {@code /v1/admin} move restores a per-principal ceiling. A
 * non-admin/unauthenticated caller is 401/403; it never runs unauthenticated.
 *
 * <h3>Idempotency (status-guarded + row-locked — never double-inserts the outbox).</h3>
 * <ul>
 *   <li>absent id -&gt; 404;</li>
 *   <li>status == PROCESSED -&gt; 200 no-op (already reprocessed; ZERO outbox saves);</li>
 *   <li>status == RECEIVED -&gt; 409 (in-flight / never failed; not a reprocess candidate);</li>
 *   <li>status == FAILED -&gt; re-insert ONE outbox row + mark PROCESSED + stamp reprocessed_at.</li>
 * </ul>
 * The row is loaded with a {@code PESSIMISTIC_WRITE} lock ({@link InboundWebhookRepository#findByIdForUpdate})
 * so the FAILED check + flip + outbox write are serialized: two concurrent admin reprocesses of the same
 * FAILED id cannot both pass the FAILED guard — the second blocks, re-reads PROCESSED, and short-circuits to
 * the 200 no-op instead of minting a second outbox event.
 *
 * <h3>Atomicity (ONE {@code @Transactional}).</h3>
 * The outbox re-insert ({@link WebhookOutboxWriter#writeOutboxRow}) and the inbound status flip
 * ({@link InboundWebhook#markReprocessed()}) commit together. If the re-insert throws, the flip rolls
 * back — the row stays FAILED and is re-drivable; no partial outbox row is committed.
 *
 * <h3>Tenant (SEC-09).</h3>
 * The outbox tenant comes from {@link WebhookOutboxWriter}, which resolves it from the server-owned
 * origin store by gateway payment id — the SAME resolution the live handler uses — NEVER from client
 * input and NEVER from the persisted {@code inbound_webhooks.tenant_id} column.
 */
@RestController
@RequestMapping("/v1/admin/webhooks")
public class WebhookReprocessController {

    private static final Logger log = LoggerFactory.getLogger(WebhookReprocessController.class);

    private final InboundWebhookRepository webhookRepository;
    private final WebhookOutboxWriter outboxWriter;

    public WebhookReprocessController(InboundWebhookRepository webhookRepository,
                                      WebhookOutboxWriter outboxWriter) {
        this.webhookRepository = webhookRepository;
        this.outboxWriter = outboxWriter;
    }

    @PostMapping("/reprocess/{id}")
    @PreAuthorize("hasRole('admin')")
    @Transactional
    public ResponseEntity<Void> reprocess(@PathVariable String id) {
        // PESSIMISTIC_WRITE: serialize the status-read + FAILED->PROCESSED flip + outbox write against a
        // concurrent admin reprocess of the SAME id. The second concurrent tx blocks here on the row lock,
        // then re-reads status==PROCESSED below and short-circuits to the 200 no-op — so it never mints a
        // second outbox event. Runs inside THIS @Transactional so the lock is held to commit.
        Optional<InboundWebhook> found = webhookRepository.findByIdForUpdate(id);
        if (found.isEmpty()) {
            // Absent id -> 404. No cross-tenant oracle concern: this is an admin-only operator surface.
            return ResponseEntity.notFound().build();
        }

        InboundWebhook webhook = found.get();
        String status = webhook.getStatus();

        // Idempotent no-op: an already-PROCESSED row must NEVER re-insert into the outbox (no double row).
        if (InboundWebhook.STATUS_PROCESSED.equals(status)) {
            log.info("reprocess no-op: inbound webhook {} already PROCESSED", id);
            return ResponseEntity.ok().build();
        }

        // A RECEIVED row is in-flight / never failed — not a reprocess candidate. 409 rather than silently
        // re-driving something that may still be committing on the live path.
        if (InboundWebhook.STATUS_RECEIVED.equals(status)) {
            log.warn("reprocess rejected: inbound webhook {} is RECEIVED (in-flight, not FAILED)", id);
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }

        // Only FAILED proceeds.
        if (!InboundWebhook.STATUS_FAILED.equals(status)) {
            log.warn("reprocess rejected: inbound webhook {} has unexpected status {}", id, status);
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }

        try {
            // Re-insert the canonical outbox row FIRST, then flip status — both in THIS one tx. A throw here
            // rolls the flip back; the row stays FAILED and is re-drivable. Never a partial outbox commit.
            String tenant = outboxWriter.writeOutboxRow(webhook.getRawPayload());
            webhook.markReprocessed();
            webhookRepository.save(webhook);
            log.info("reprocessed inbound webhook {} (event_id={}) -> outbox re-inserted for tenant={}",
                    id, webhook.getEventId(), tenant);
            return ResponseEntity.ok().build();
        } catch (JsonProcessingException e) {
            // A malformed persisted payload cannot be re-driven. Roll back (row stays FAILED) and surface a
            // 422 so the operator knows this row needs manual attention, not a blind retry.
            log.error("reprocess failed: inbound webhook {} raw_payload is unparseable", id, e);
            throw new io.nexuspay.common.exception.InvalidRequestException(
                    "Inbound webhook " + id + " has an unparseable persisted payload and cannot be reprocessed");
        }
    }
}
