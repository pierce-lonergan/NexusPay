package io.nexuspay.vault.application.service;

import io.nexuspay.common.crypto.EncryptionPort;
import io.nexuspay.common.rls.SystemTransactional;
import io.nexuspay.common.rls.TenantWorkRunner;
import io.nexuspay.vault.application.port.out.VaultRepository;
import io.nexuspay.vault.config.VaultProperties;
import io.nexuspay.vault.domain.VaultedCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * GAP-059: background encryption key-rotation job. Pages through cards still encrypted under a
 * RETIRED key and re-encrypts each onto the CURRENT active key, so a key rotation is actually
 * carried through the vault instead of being a dead letter.
 *
 * <p><b>OFF by default — structurally.</b> The bean is annotated
 * {@code @ConditionalOnProperty(nexuspay.vault.key-rotation.enabled=true)}, so it is NOT even
 * created unless explicitly enabled (stricter than a runtime boolean check — a disabled deployment
 * has no scheduled method at all). Even when enabled, the cycle no-ops unless a
 * {@code retiredKeyId} is configured (a blank/absent value returns immediately). The ACTIVE key is
 * resolved via {@link EncryptionPort#currentKeyId()} — never hardcoded, never configured here.</p>
 *
 * <p><b>Safety posture (money/PCI-critical), mirrors {@code RefundReconciler}:</b>
 * <ul>
 *   <li><b>Atomic per card</b> — the actual re-encrypt is {@code CardVaultService.rotateCardKey},
 *       a single {@code @Transactional} decrypt→re-encrypt→verify-decrypt-after→persist; a card is
 *       never left with ciphertext that disagrees with its stamped key id.</li>
 *   <li><b>Idempotent</b> — a card already on the active key (or otherwise off the retired key) is
 *       skipped without decrypting; a re-run or racing replica is a safe no-op.</li>
 *   <li><b>Page-bounded</b> — {@code findCardsByEncryptionKeyId(retired, batchSize)} loads at most
 *       one batch at a time, so a huge vault cannot OOM the loop. Rotated cards flip off the retired
 *       key and drop out of the next page, so successive pages drain the set.</li>
 *   <li><b>Tenant-scoped, RLS-forward</b> — discovery runs as SYSTEM
 *       ({@code @SystemTransactional}) so the cross-tenant finder sees every tenant's cards (vault
 *       RLS is dormant today but this stays correct on activation); each per-card rotation is bound
 *       to the card's OWN tenant via {@link TenantWorkRunner#callInTenant} so RLS WITH CHECK scopes
 *       the write.</li>
 *   <li><b>Per-card failure isolation</b> — each rotation is wrapped in try/catch; on failure the
 *       {@code failed} counter is incremented, an ERROR is logged (card id + tenant + key ids ONLY,
 *       never PAN), the card is left UNTOUCHED on the retired key (its tx rolled back, still usable,
 *       re-selected next cycle), and the loop CONTINUES so one bad card cannot block rotating the
 *       rest.</li>
 * </ul></p>
 *
 * <p>No cross-instance lock is used: rotation is idempotent, so two replicas racing on the same card
 * both yield a valid card on the active key (the loser SKIPs). A lock would be defense-in-depth, not
 * correctness, and vault has no Redis wiring — deliberately out of scope here.</p>
 */
@Service
@ConditionalOnProperty(name = "nexuspay.vault.key-rotation.enabled", havingValue = "true")
public class KeyRotationJobService {

    private static final Logger log = LoggerFactory.getLogger(KeyRotationJobService.class);

    private final VaultRepository repository;
    private final CardVaultService cardVaultService;
    private final EncryptionPort encryption;
    private final VaultKeyRotationMetrics metrics;
    private final TenantWorkRunner tenantWork;
    private final VaultProperties properties;

    public KeyRotationJobService(VaultRepository repository,
                                 CardVaultService cardVaultService,
                                 EncryptionPort encryption,
                                 VaultKeyRotationMetrics metrics,
                                 TenantWorkRunner tenantWork,
                                 VaultProperties properties) {
        this.repository = repository;
        this.cardVaultService = cardVaultService;
        this.encryption = encryption;
        this.metrics = metrics;
        this.tenantWork = tenantWork;
        this.properties = properties;
    }

    /**
     * Runs on a fixed delay (default 5 min). Discovery is SYSTEM-pinned so it sees all tenants; each
     * per-card rotation is bound to its own tenant. The whole method is a no-op unless a retired key
     * is configured.
     */
    @SystemTransactional
    @Scheduled(fixedDelayString = "${nexuspay.vault.key-rotation.fixed-delay-ms:300000}")
    public void rotateRetiredKeys() {
        String retiredKeyId = properties.getKeyRotation().getRetiredKeyId();
        if (retiredKeyId == null || retiredKeyId.isBlank()) {
            log.debug("Key rotation enabled but no retiredKeyId configured — nothing to rotate");
            return;
        }

        String activeKeyId = encryption.currentKeyId();
        if (retiredKeyId.equals(activeKeyId)) {
            log.warn("Key rotation retiredKeyId '{}' equals the active key — nothing to rotate", retiredKeyId);
            return;
        }

        int batchSize = properties.getKeyRotation().getBatchSize();
        if (batchSize <= 0) {
            batchSize = 100;
        }

        log.info("Key rotation cycle starting: retiredKeyId={}, activeKeyId={}, batchSize={}",
                retiredKeyId, activeKeyId, batchSize);

        long rotated = 0;
        long skipped = 0;
        long failed = 0;

        // Page until a page comes back smaller than the batch. Rotated (and failed-but-untouched)
        // cards behave differently: a ROTATED card flips off the retired key and drops out of the
        // next query; a FAILED card stays on the retired key and WOULD be re-selected, which could
        // loop forever on a permanently-bad card. To guarantee forward progress within a single
        // cycle, we stop paging once a page yields zero SUCCESSFUL rotations (only failures/skips
        // remain) — those are surfaced via the failed metric + ERROR log and re-driven next cycle.
        while (true) {
            List<VaultedCard> page = repository.findCardsByEncryptionKeyId(retiredKeyId, batchSize);
            if (page.isEmpty()) {
                break;
            }

            long rotatedThisPage = 0;
            for (VaultedCard card : page) {
                try {
                    CardVaultService.RotationOutcome outcome =
                            tenantWork.callInTenant(card.getTenantId(),
                                    () -> cardVaultService.rotateCardKey(card.getId(), retiredKeyId));
                    if (outcome == CardVaultService.RotationOutcome.ROTATED) {
                        rotated++;
                        rotatedThisPage++;
                        metrics.recordRotated();
                    } else {
                        skipped++;
                        metrics.recordSkipped();
                    }
                } catch (Exception e) {
                    // One card's failure must never abort the sweep. The card is left untouched on the
                    // retired key (its rotateCardKey tx rolled back), remains usable, and is re-driven
                    // next cycle. Log id/tenant/keys ONLY — never the PAN.
                    failed++;
                    metrics.recordFailed();
                    log.error("Key rotation failed for card={} tenant={} retiredKeyId={}: {}",
                            card.getId(), card.getTenantId(), retiredKeyId, e.getMessage(), e);
                }
            }

            // Forward-progress guard: if a full page produced no successful rotation, the remainder is
            // failures/skips that will not drain — stop this cycle rather than spin.
            if (rotatedThisPage == 0) {
                break;
            }
        }

        log.info("Key rotation cycle complete: rotated={}, skipped={}, failed={} (retiredKeyId={})",
                rotated, skipped, failed, retiredKeyId);
    }
}
