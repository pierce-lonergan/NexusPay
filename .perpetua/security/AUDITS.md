# AUDITS — deep-audit log

## 2026-06-09 — Bootstrap audit (manual, 6 parallel review agents)
Tools: manual multi-agent source review across all 16 modules (no automated
scanners yet — gitleaks/OSV/semgrep pending B-006). Disposition below.

FIXED this session (commit 4a1c6ea):
- Webhook HMAC non-constant-time compare → `MessageDigest.isEqual` (L-007).
- Evidence storage path traversal → segment sanitize + root check (L-008).
- Vault unkeyed PAN fingerprint → HMAC; 8→6 digit BIN (L-009).
- Idempotency cross-merchant key leak + 5xx caching (L-010).
- Cross-currency "balanced" ledger entries (L-001); FRM polarity/exponent
  (L-005/006); double-billing-adjacent money bugs (L-004).

OPEN — HIGH (tracked in BACKLOG, preempt feature work per §15.3):
- **RLS inert at runtime** — `SET LOCAL` outside a tx + app connects as table
  owner (bypasses RLS). Cross-tenant exposure. → B-002 (research-first).
- **Fraud BLOCK + sanctions screening gate nothing** — `assess()` /
  `validateOrThrow()` have zero callers in the payment path. → B-003 (RFC-first).
- **Secrets default to committed dev values, no prod guard** → B-004.
- **Maker-checker refund**: null idempotency key + no execute-once → dup/lost
  refund. → B-009.
- **Dispute webhook**: no signature verification, no idempotency → duplicate
  disputes/reserves + unauth tenant action. → BACKLOG (to be scored).
- **Billing schedulers**: no distributed lock → multi-instance double-billing.
  → B-001.

DEAD-CODE / ARCHITECTURE (not a vuln; tracked):
- Routing A/B framework unreachable (B-007): `selectConfig`/`recordOutcome` have
  no callers; z-test correct but never fed. Wire-vs-delete escalated to Q-007.
  Stats locked by RoutingAbTestServiceTest so neither path regresses the math.

OPEN — MEDIUM:
- Reconciliation settlement ingest INSERT fails (jsonb mapping) → B-010.
- Reconciliation PARTIAL (missing-ledger) silently swallowed → B-008.
- Flyway V1/V2 cross-module version collisions → B-011.
- Pagination DoS (`limit=0` div-by-zero; offset-as-page misuse) → BACKLOG.
- DCC/rate-lock/A-B mutable singleton state breaks multi-instance → BACKLOG.

## 2026-06-10 — Baseline scans (B-006, partial)
Tooling not installable in this sandbox (no gitleaks/osv/semgrep binaries), so:
- **Local secret baseline (real, manual):** `git grep` over all tracked files for
  AWS keys, PEM private keys, Slack/GitHub/Stripe live tokens, and hardcoded
  literal `password|secret|api_key|token = "…"` in non-test Java. RESULT: CLEAN —
  no live-secret patterns; the only "secrets" are the `${ENV:default}` dev
  placeholders in application.yml, which are intentional and now fail-fast-guarded
  in prod (B-004). No finding.
- **CI scanners wired** (run on push, gate merges): gitleaks (secret-scan job) +
  OSV-Scanner (dependency-scan job, fails on High+ CVEs per high_vulns_max=0).
  semgrep still PENDING (B-006 remainder).
- **Dependency posture:** versions are centralized in gradle/libs.versions.toml;
  no lockfile committed (OSV scans the resolved graph in CI). First OSV run's
  findings → triage here + BACKLOG, never silent-suppress.
STATUS: local baseline clean; full automated scan results pending the first CI run
(needs push — Q-001) or local tool install (Q-003).

## 2026-06-10 — first REAL automated scans ran in CI (B-006 operational, gate GREEN)
Both scanners now run in perpetua-gates as pinned binaries (gitleaks 8.30.1,
osv-scanner 2.3.8); the previous marketplace-action versions were broken.
- **gitleaks (secret-scan, BLOCKING):** working-tree scan, 1 finding → triaged
  FALSE POSITIVE, allowlisted in `.gitleaks.toml` (scoped to one file). It was
  `StartupSecretsValidator.java`, which embeds the dev-default secret literals BY
  DESIGN (the B-004 detect-and-refuse control); public placeholders, not
  credentials. No real secrets in the working tree. (History scan still TODO.)
- **osv-scanner (dependency-scan, REPORT-ONLY first run, §15.3):** 7 known vulns
  (1 Critical / 1 High / 5 Medium), ALL in `checkout-sdk/package-lock.json`
  (frontend SDK **dev** deps), NONE in the Java backend runtime graph → tracked as
  **B-023**; flip the gate to blocking after the bump. Java/Gradle deps: no High+.
- Also FIXED (now CLOSED, no longer just MEDIUM-open): B-010 jsonb, B-008 PARTIAL,
  **B-011 Flyway** (+ the whole schema↔entity drift behind it) — all verified by the
  now-green integration suite. semgrep SAST still PENDING.

NEXT DEEP AUDIT should: run gitleaks over git history (not just working tree),
wire semgrep java rulesets, triage+fix B-023 then enforce OSV, and re-verify the
OPEN-HIGH items above are closed or still tracked. Log results here.

## 2026-06-14 — semgrep Java SAST wired into CI + real pre-triage (B-006 remainder)
Tool: semgrep 1.166.0. Native Windows can't run it (semgrep-core's OCaml RPC
needs Unix `socketpair`, fails on Win even under git-bash; no Windows wheel;
docker/pipx absent). RAN IT FOR REAL in WSL2 (Ubuntu, kernel 6.6, x86_64) via a
`python3 -m venv` + `pip install semgrep`; repo reachable at /mnt/c/...; registry
fetch worked. CI itself uses the pinned `semgrep/semgrep:1.166.0` Linux image
(no socketpair issue), so the local-Win limitation is irrelevant to the gate.

CI STEP ADDED — `.github/workflows/perpetua-gates.yml`, new `sast-scan` job
(mirrors dependency-scan's pinned-binary, report-only style):
- `runs-on: ubuntu-latest`, `container: semgrep/semgrep:1.166.0` (PINNED tag; not
  the floating marketplace action — same rationale as gitleaks/osv). @sha256
  digest pin deferred to B-012 with the other pins.
- `semgrep scan --config p/java --config p/owasp-top-ten --config p/secrets
  --metrics off --include '*.java' --exclude build --exclude '**/build/**'
  --exclude '**/generated/**' --exclude '**/test/**' --exclude gatling
  --exclude checkout-sdk --error --sarif --output semgrep.sarif .`
- REPORT-ONLY on first run (§15.3): trailing `|| echo "::warning::...not blocking
  yet"` swallows semgrep's non-zero exit so the build stays GREEN; SARIF uploaded
  as artifact for review. Excludes: 16 modules' build/ (jacoco + generated avro
  classes), generated sources, all test trees, gatling, and checkout-sdk (Node/TS
  — out of Java SAST scope). YAML validated (parses; 4 jobs).

PRE-TRIAGE (REAL semgrep runs in WSL2 over git-tracked Java, 703 files, ~100% parse):
- PASS A — p/java + p/owasp-top-ten + p/secrets (100 rules): **0 Java findings**.
- PASS B — p/security-audit + p/sql-injection + p/command-injection + p/xss
  (61 rules): **0 Java findings**.
- NET: semgrep OSS finds ZERO Java SAST issues. No HIGH-confidence real issue to
  fix → no code changes made (correctly: nothing to fix).

OUT-OF-SCOPE FINDINGS (TypeScript checkout-sdk; NOT Java SAST, excluded from the
CI scan; recorded here for completeness, not blocking):
- `checkout-sdk/.../iframe-manager.ts:208` and `card-frame.ts:38-41` —
  `postMessage(msg, '*')` wildcard target origin (rule:
  wildcard-postmessage-configuration, WARNING). TRIAGE: REAL but LOW severity —
  parent↔own-iframe only, and the receiver validates `data.source` (e.g.
  'nexuspay-card-frame'/'nexuspay-parent'). DISPOSITION: ACCEPTED for now; tighten
  target origin to the known frame origin under a separate JS/SDK item (not B-006).

REGRESSION RE-CHECK — prior SAST-class fixes ALL STILL FIXED (read the source):
- L-007 webhook HMAC constant-time: HyperSwitchWebhookController.java:154-171 uses
  `MessageDigest.isEqual` on HmacSHA512 hex (not String.equals). INTACT.
- L-008 evidence path traversal: LocalEvidenceStorageAdapter.java:70-91 —
  per-segment `safeSegment()` + `resolveWithinRoot()` normalize + `startsWith(root)`
  reject (defense in depth). INTACT.
- L-009 PAN fingerprint HMAC-keyed: AesGcmEncryptionAdapter.java:97-119 — HmacSHA256
  keyed by a domain-separated SHA-256(masterKey||"fingerprint"). INTACT.
- Crypto/SQL/deserialization/SSRF/cmd-injection/XXE: clean (consistent with 0
  semgrep findings). Math.random() hits are all cosmetic mock last4 in network-token
  STUB adapters (note: use SecureRandom when real impls land — not a current vuln).

NEXT: after the first report-only CI run confirms 0 on the runner, FLIP the gate to
blocking (drop the `|| echo`, add a semgrep ratchet) — the Java surface is already
0-finding so it can gate at high/error severity immediately.

## 2026-06-15 — Comprehensive adversarial code sweep (ultracode workflow w7s9d8yxw)

6 diverse auditor lenses -> refute-default verification (fresh-context skeptic per finding) -> synthesis. 55 raw -> 48 CONFIRMED (real+reachable+unmitigated) -> 22 deduplicated remediations. Severity of confirmed: 13 CRITICAL, 17 HIGH, 12 MEDIUM, 6 LOW (45 T3).

**Dominant root cause:** every module EXCEPT gateway-api derives tenant authority from a client-supplied `X-Tenant-Id` header; by-id reads are global PK lookups ignoring tenant; there is NO authorization aspect/@Where/@Filter; and RLS is dormant (rls.enforce=false). So application-level checks are the ONLY tenant isolation and they are missing across vault (cardholder data), marketplace payouts (money), disputes (chargeback ledger), ledger queries, and the payment lifecycle. The app-level fixes below make the system safe REGARDLESS of RLS state and must land first; the RLS cutover (B-002-cutover) remains human-gated but is now urgent.


Ids below are the workflow's own numbering; tracked in BACKLOG under the SEC- prefix to avoid colliding with B-0xx.


### B-001 [CRITICAL/T3] Unauthenticated, unsigned, non-idempotent dispute webhook drives real chargeback ledger postings (infinitely replayable)
- **Where:** dispute/src/main/java/io/nexuspay/dispute/adapter/in/webhook/DisputeWebhookHandler.java:50-94; iam SecurityConfig.java:52; DisputeLifecycleService.java:70,149,166,190; LedgerChargebackAdapter.java:39-82
- **Fix:** Add PSP HMAC verification exactly as HyperSwitchWebhookController (HMAC-SHA512 over raw body, constant-time MessageDigest.isEqual), fail-closed 401 on missing/invalid signature. Add replay protection: dedup by external_dispute_id (Valkey SET-NX like the payment webhook) AND make openDispute idempotent on (tenantId, externalDisputeId) by looking up an existing dispute and no-op/updating instead of minting a fresh dispute id. Stop trusting X-Tenant-Id; derive tenant from the verified payload. Network-restrict /internal/** to PSP egress as defense-in-depth. NOTE: ledger postings currently hit EnsureAccountsExistUseCase.DEFAULT_TENANT (LedgerChargebackAdapter.java:71), so today's blast radius is the default-tenant chargeback books + unbounded fake disputes, not the named victim's ledger — core authn/replay defect is fully real.

### B-002 [CRITICAL/T3] Tenant authority derived from client-supplied X-Tenant-Id header across all non-gateway modules (cross-tenant IDOR/escalation, RLS dormant)
- **Where:** marketplace PayoutController.java:32,46,57 / SplitPaymentController.java:32,52 / ConnectedAccountController.java:34; b2b VendorPaymentController.java:33,43,53 / VirtualCardController.java:33,52,62,72; vault VaultController.java:42,57,71; fraud FraudRuleController.java:36; TenantContextFilter.java:50-83; app/application.yml:129 (rls.enforce=false)
- **Fix:** Remove @RequestHeader("X-Tenant-Id") from every authenticated controller and source tenant exclusively from @AuthenticationPrincipal NexusPayPrincipal.tenantId() / TenantContext — the pattern gateway-api PaymentController (B-029) already uses. If the header must remain, add a global filter rejecting (403) any request whose X-Tenant-Id != principal.tenantId(). @PreAuthorize only checks role and must not be relied on for tenant scoping. Umbrella fix enabling the per-resource ownership checks in B-005..B-009. Independently prioritize the rls.enforce=true cutover as a DB backstop.

### B-003 [CRITICAL/T3] Checkout SDK card iframe accepts forged postMessages (no event.origin check) and lets STYLE_UPDATE override apiBase/sessionToken — raw PAN + bearer token exfiltration
- **Where:** checkout-sdk/packages/js/src/elements/card-frame.ts:219-235, 178-193; production copy card-frame.html:381-431; gateway-api CheckoutSecurityHeadersFilter.java:40-41 (frame-ancestors *)
- **Fix:** In handleParentMessage validate event.origin against the known parent/SDK origin AND event.source === window.parent; treat data.source as a non-security hint. Never accept apiBase or sessionToken via postMessage — bake apiBase into the frame build/URL as an immutable constant, obtain the session token only over a trusted channel, and reject STYLE_UPDATE payloads that mutate apiBase/sessionToken. Tighten CheckoutSecurityHeadersFilter from frame-ancestors * to an explicit merchant allowlist and add a connect-src CSP so the frame cannot fetch to arbitrary origins. Drop allow-same-origin or serve the frame from a distinct origin. Apply the symmetric origin check on the parent side.

### B-005 [CRITICAL/T3] Vault card retrieval/deletion/cryptogram/network-token provisioning ignore tenant ownership — cross-tenant cardholder-data disclosure, destruction, and payment-grade cryptogram generation
- **Where:** vault CardVaultService.java:105-117 (getCard), 119-137 (deleteCard); NetworkTokenService.java:44-78 (provision), 82-102 (generate); VaultRepositoryAdapter.java:43-45,70-72,92
- **Fix:** After loading the token/card/network-token, assert resource.getTenantId().equals(authenticatedTenant) (from the principal per B-002, NOT X-Tenant-Id) and throw not-found on mismatch. Switch repository lookups to tenant-scoped finders (findByIdAndTenantId). In generate(), resolve the network token's card and verify card/token tenant == caller tenant before invoking the network port; in provision(), verify the source vault token's tenant matches the caller and do not derive ownership from the request. Stop leaking vaultTokenId in INFO logs / domain-event payloads, the disclosure vector for the token ids.

### B-006 [CRITICAL/T3] Marketplace payout/account/split operations not tenant-scoped — cross-tenant payout creation (money misdirection) and account suspend/close/read IDOR
- **Where:** marketplace PayoutService.java:44-92 (createPayout), 96-100 (listPayouts), 104-107 (getPayout); AccountOnboardingService.java:80-126; MarketplaceRepositoryAdapter.java:109-117
- **Fix:** In createPayout, after loading the connected account, reject unless account.getTenantId().equals(command.tenantId()); validate connected-account/vendor ownership in createSplitPayment. Make every by-id lookup tenant-scoped (findAccountByIdAndTenantId / findPayoutByIdAndTenantId) and assert payout.getTenantId() on read paths (getPayout/listPayouts currently ignore the arg). Apply load-then-assert to getAccount/updateAccount/suspendAccount/closeAccount. Source tenant from the principal (B-002). connectedAccountId is not a secret, so today's only friction is ACTIVE-status + minimum-amount.

### B-004 [HIGH/T3] Full PAN persisted base64-encoded (reversible, unencrypted) in payment_tokens.token_data via SDK tokenize path
- **Where:** gateway-api TokenizationService.java:85-94; CheckoutController.java:65-67; PaymentTokenEntity.java:46-47; V3016__create_payment_tokens.sql:15; source card-frame.ts:191
- **Fix:** Never transmit/store the raw PAN from the SDK. Route the SDK tokenize path through the encrypted vault as CardVaultService does (EncryptionPort AES-GCM) and set encryptionKeyId, or have the frame send only a vault token reference. At minimum encrypt token_data with the vault EncryptionPort and persist the keyId. Add a startup assertion / DB CHECK rejecting payment tokens whose encryptionKeyId is null so a plaintext-PAN regression fails closed. Reportable PCI DSS Req 3 (cardholder data recoverable at rest).

### B-007 [HIGH/T3] Payment lifecycle IDOR — get/capture/cancel/confirm/refund forward the gateway id straight to the PSP with no tenant ownership check (incl. sub-threshold cross-tenant refund bypassing maker-checker)
- **Where:** payment-orchestration HyperSwitchPaymentAdapter.java:74-96,100-138,142-194; gateway-api GatedPaymentGateway.java:247-274; PaymentController.java:112-136,146-164; RefundOrchestrationService.java:43-63
- **Fix:** Before any get/capture/cancel/confirm/refund, verify the principal's tenant owns the gateway payment id. The mapping already exists — ScreeningOriginService maps gatewayPaymentId -> (tenantId, mode), populated at create (GatedPaymentGateway:138) but only used at confirm-time re-screening; call ScreeningOriginService.find(paymentId).tenantId() == principal.tenantId() (404 on mismatch) on ALL lifecycle paths. This also closes the sub-threshold cross-tenant refund: createRefund routes amount<50000 directly to the PSP with no approval and no ownership check, so a tenant-A operator can refund tenant-B funds with amount=49999. Consider requiring approval for anomalous/cross-tenant refunds regardless of amount.

### B-008 [HIGH/T3] Ledger journal-entry queries leak all tenants' financial records (no tenant scope on getByPaymentReference/getByDateRange)
- **Where:** gateway-api LedgerController.java:46-66; ledger GetJournalEntriesUseCase.java:29-37; JpaJournalEntryRepository.findByPaymentReference/findByDateRange; JournalEntryEntity.java:32
- **Fix:** Add a tenantId parameter sourced from principal.tenantId() to getByPaymentReference/getByDateRange and add WHERE tenant_id = :tenantId to the underlying @Query methods, mirroring listAccounts in the same controller (which scopes via GetBalanceUseCase.getAllBalances(principal.tenantId())). Any authenticated user can currently GET /v1/ledger/journal-entries?from=1970-01-01T00:00:00Z and receive every tenant's double-entry lines.

### B-009 [HIGH/T3] Cross-tenant webhook event fan-out in default (RLS-dormant) configuration — every tenant's payment events delivered to any tenant's subscribed endpoint
- **Where:** gateway-api WebhookDeliveryService.java:84-106 (rlsEnforced=false -> findAllByEnabledTrue), 124-128; OutboxRelay.java:140-143
- **Fix:** Filter delivery endpoints by the event's tenant UNCONDITIONALLY (resolve tenant from the event and call findAllByTenantIdAndEnabledTrue) regardless of rls.enforce — this is an application-level authorization concern. The tenant-scoped finder already exists but is gated behind rlsEnforced. Also fix the producer to stamp a trustworthy tenant_id header on every payments/refund outbox event (OutboxRelay sets only event_type/aggregate headers, so the enforced branch resolves to "default"), so the filter is correct before it is relied on.

### B-010 [HIGH/T3] Ledger capture/refund idempotency is a check-then-act with no DB unique constraint — duplicate Kafka delivery double-posts the ledger
- **Where:** ledger PaymentEventConsumer.java:87-113 (capture), 127-153 (refund); V1001__create_ledger_schema.sql:23,30; CreateJournalEntryUseCase.java:38
- **Fix:** Add a DB unique constraint backing the idempotency key (UNIQUE(payment_reference, description) or a dedicated idempotency column) and treat the duplicate-key violation as a no-op. The existsByPaymentReferenceAndDescription SELECT runs in auto-commit BEFORE the SERIALIZABLE insert tx (TenantWorkRunner opens no enclosing tx), so two redeliveries both pass the check and both post distinct balanced entries; SERIALIZABLE protects the balance read-modify-write but not two separate valid entries, and the Kafka 3x retry re-runs the balance update without re-checking the guard. Alternatively perform the existence check inside the same SERIALIZABLE tx as the insert, and key consumption by payment id on a single partition.

### B-011 [HIGH/T3] PayoutScheduler has no distributed lock + whole-batch transaction -> double real-money payouts on multi-replica (latent behind stub rail)
- **Where:** marketplace PayoutService.java:114-155 (processPendingPayouts); PayoutScheduler.java:31-36; JpaPayoutRepository.findByStatusAndScheduledAtBefore; PayoutExecutionPort.java:18-24
- **Fix:** Wrap processPendingPayouts in SchedulerLock.runExclusively (fail-closed, as billing RenewalScheduler does). Claim each payout atomically with a conditional UPDATE ... SET status=PROCESSING WHERE id=? AND status=PENDING (act only on rowcount=1) or SELECT ... FOR UPDATE SKIP LOCKED; commit the PROCESSING transition in its own REQUIRES_NEW tx BEFORE calling the executor (not one batch tx). Add a deterministic idempotency key (payout id) to PayoutExecutionPort so the rail dedupes. MUST land before GAP-062 wires a real disbursement rail — today PayoutExecutionStubAdapter moves no money so the double-pay is latent, but the race is real now. (The single-replica self-overlap claim from one source is wrong: @Scheduled(fixedDelay) is single-threaded and cannot overlap its own ticks; only the multi-replica path is valid.)

### B-013 [HIGH/T2] checkout-sdk iframe-manager sends session token with targetOrigin '*' and accepts inbound iframe messages without origin/source validation (token leak + token-id spoofing)
- **Where:** checkout-sdk/packages/js/src/elements/iframe-manager.ts:206-209, 112-119, 211-219
- **Fix:** Replace postMessage(msg, '*') with the explicit card-frame origin (new URL(apiBase).origin) on every parent->iframe message, especially the one carrying sessionToken. In isValidMessage verify event.origin === expected card-frame origin AND event.source === this.iframe.contentWindow before dispatching; treat data.source as a non-security hint. Prefer a per-session MessageChannel established at FRAME_READY. The inbound spoof (forged TOKENIZE_RESPONSE {success:true, tokenId:'attacker'}) is reachable by any co-resident frame and substitutes an attacker-chosen payment instrument. T2: checkout-sdk is TS, outside the Java SAST T3 globs, but a PCI card boundary. (The AUDITS.md-accepted wildcard item — risk-acceptance is not a guard; the inbound-spoof path was never analyzed there.)

### B-014 [HIGH/T3] Outbound webhook delivery POSTs to merchant-supplied URLs with no SSRF/scheme/host validation
- **Where:** gateway-api WebhookDeliveryService.java:112-119; CreateWebhookEndpointRequest.java:11-12 (@NotBlank only); WebhookEndpointController.java:42-47
- **Fix:** Validate the URL at registration AND again before delivery (defeat DNS rebinding): require https, parse the host, resolve it, reject RFC1918/loopback/link-local/ULA and the 169.254.169.254 metadata range; reject non-standard ports. Pin the resolved public IP for the connection or route webhook egress through a deny-by-default proxy. Tenant-admin is a per-merchant self-service role, so the SSRF is reachable by any merchant admin; it is blind (responses discarded) but enables internal POSTs, port/host recon, and cloud-metadata reach. (With RLS dormant the same endpoint also receives all tenants' payloads — see B-009.)

### B-016 [HIGH/T3] DeadLetterReprocessor flips status to RETRYING then mutates final state only in an async callback after the tx/lock end — dead letters stuck in RETRYING forever (lost recovery)
- **Where:** app/src/main/java/io/nexuspay/app/event/DeadLetterReprocessor.java:82-111; DeadLetterRepository.java:26-29 (findRetryable selects PENDING only)
- **Fix:** Block on the send acknowledgment on the scheduler thread (.get(timeout), as OutboxRelay.java:155/163 does) and perform the RESOLVED/PENDING/DISCARDED status mutation synchronously inside the transactional, lock-held scope. Alternatively add a reclaimer that resets stale RETRYING rows (older than N minutes) back to PENDING in findRetryable. Today a pod restart between Kafka dispatch and the producer-thread callback leaves the entry RETRYING permanently; findRetryable never re-selects it, so an already-failed PaymentCaptured/RefundCompleted is never re-booked to the ledger.

### B-012 [MEDIUM/T3] Capture/void/refund rely on a caller-supplied, optional idempotency key — null key => no PSP dedup => double capture/refund on retry
- **Where:** gateway-api RefundOrchestrationService.java:43-63; PaymentController.java:112-136,146-164; HyperSwitchPaymentAdapter.java:100-117,155-179; IdempotencyFilter (no-op when header absent)
- **Fix:** Derive a deterministic server-side idempotency key for capture/void/refund when the caller omits one (e.g. capture-<paymentId>-<amount>, refund-<paymentId>-<amount>-<reasonHash>), mirroring the approved-refund refund-approval-<id> pattern (B-009), so retries dedupe at HyperSwitch regardless of client behavior. Consider making the Idempotency-Key header required on these mutating money endpoints. No DB-level refund/capture uniqueness constraint exists either, so add one as a backstop.

### B-015 [MEDIUM/T3] HyperSwitch webhook marks Valkey dedup key BEFORE the DB transaction commits — a rolled-back webhook permanently suppresses redelivery (lost capture/refund)
- **Where:** payment-orchestration HyperSwitchWebhookController.java:71-145 (setIfAbsent 106-112; outbox/webhook saves 114-145)
- **Fix:** Do not let a non-transactional external-store mark gate a not-yet-durable DB write. Either (a) move dedup to a DB-backed unique key written in the SAME transaction as the outbox row (unique on inbound_webhooks.event_id with insert-and-catch-duplicate — the column is already UNIQUE NOT NULL but the Valkey fast-path short-circuits before the insert on replay), or (b) register the Valkey dedup write only via a TransactionSynchronization afterCommit hook. Today a commit-time DB failure after the SET-NX leaves the 24h key set with no outbox event, so HyperSwitch's redelivery is dropped as duplicate and the capture/refund is silently lost from the ledger pipeline.

### B-017 [MEDIUM/T3] Reconciliation run.fail() is saved inside the same transaction that then rolls back — failed runs leave no durable record
- **Where:** reconciliation ReconciliationOrchestrator.java:52-70 (@Transactional entry points), 106-111 (catch: run.fail(); saveRun; throw)
- **Fix:** Persist the FAILED status in a separate REQUIRES_NEW transaction (or move the try/catch outside the @Transactional boundary) so the failure audit commits independently of the work rollback — mirror ParseFailureRecorder in this same codebase, which already uses @Transactional(REQUIRES_NEW) for exactly this reason and documents that REQUIRED writes 'vanish' on caller rollback. Today a malformed settlement file rethrows, marking the tx rollback-only, so the FAILED write (and the whole run row) is erased and the failed reconciliation batch leaves no trace to investigate/retry.

### B-018 [MEDIUM/T1] Analytics rollup consumer applies additive upserts with no idempotency — revenue/auth-rate inflation on Kafka redelivery or DLT replay
- **Where:** analytics PaymentEventAnalyticsConsumer.java:57-90,92-179; AnalyticsRepositoryAdapters.java:136-147,214-221 (ON CONFLICT DO UPDATE SET col = col + EXCLUDED.col)
- **Fix:** Make rollup application idempotent: dedup by the EventEnvelope.eventId already carried on every event (currently unused here) via a processed-events table or a Valkey SET-NX keyed by (tenant, event_id) within the same tenant transaction, and skip already-applied events before incrementing. The sibling ledger consumer already guards with an existence check; the analytics consumer is the outlier. Impact is reporting integrity only (no funds move), but rollups feed anomaly detection / PSP health scoring, so inflation can drive spurious alerts or mask real ones — hence not LOW.

### B-019 [MEDIUM/T3] Webhook-endpoint DELETE is not tenant-scoped — admin of one tenant can disable another tenant's webhooks (cross-tenant denial-of-notification)
- **Where:** gateway-api WebhookEndpointController.java:64-73 (delete via findById(id), no tenant check)
- **Fix:** Load via a tenant-scoped finder findByIdAndTenantId(id, principal.tenantId()) (or verify entity.getTenantId().equals(principal.tenantId()) before setEnabled(false)) and return 404 on mismatch — mirror create()/list() in the same controller, which already scope by principal.tenantId(). @PreAuthorize hasRole('admin') is tenant-agnostic and RLS is dormant, so any tenant's admin can soft-disable a foreign endpoint, silently cutting off the victim merchant's payment/refund webhooks. The handler also returns 204 even when no row matched, masking the action.

### B-020 [MEDIUM/T3] Vault migration status readable across tenants (accept-but-ignore tenantId IDOR)
- **Where:** vault VaultMigrationService.java:49-54 (getMigrationStatus, tenantId unused); VaultRepositoryAdapter.java:115-117 (bare findMigrationById)
- **Fix:** After loading the migration, assert migration.getTenantId().equals(authenticated principal tenant) and 404 otherwise; use a tenant-scoped finder. Apply consistently across all vault service methods that currently accept-but-ignore tenantId (same root pattern as B-005). Leaked data is migration metadata (source provider, total/migrated/failed card counts) revealing a victim's vault-portfolio size — not PANs — hence MEDIUM.

### B-021 [LOW/T3] 3DS challenge handler: unvalidated redirect/iframe URL and inbound CHALLENGE_COMPLETE accepted without origin check
- **Where:** checkout-sdk/packages/js/src/three-ds/challenge-handler.ts:56-64, 125-126, 139-152
- **Fix:** Validate nextAction.url is an absolute https URL on an allowed ACS/PSP host before navigating or framing (reject javascript:/data:/relative). Check event.origin against the expected 3DS origin before honoring CHALLENGE_COMPLETE and verify event.source. Treat the client 3DS signal as advisory only. LOW because nextAction.url comes from the trusted NexusPay backend over TLS (no lower-priv path sets it) and useConfirmPayment re-confirms server-side on 'succeeded', so a forged message yields only client-side UI/flow spoof, not a real 3DS/auth bypass; defense-in-depth hardening.

### B-022 [LOW/T3] Lower-severity correctness/robustness defects: float fee math, split-payment non-idempotency, static master key, API-key prefix collision, /internal rate-limit bypass
- **Where:** marketplace PlatformFee.java:42-45 & SplitPayment.java:80; SplitPaymentService.java:36-118 + V4002:39; vault AesGcmEncryptionAdapter.java:113-133; iam ApiKeyService.java:69-75 + JpaApiKeyRepository:10; gateway-api RateLimitFilter.java:117-132
- **Fix:** (1) Fee/percentage math in BigDecimal with explicit RoundingMode (HALF_EVEN), longValueExact only at the end — the (long) cast systematically under-collects platform fees. (2) Make split creation idempotent: lookup findSplitPaymentsByPaymentId first and/or add UNIQUE(tenant_id, payment_id) — duplicate POST currently creates duplicate splits + PlatformFee rows (bookkeeping noise only today; no payout path consumes splits). (3) Adopt envelope encryption (per-record DEK wrapped by a KMS/HSM KEK) so master-key 'rotation' provides real containment; complete the HSM adapter for prod. (4) Store/query a longer indexed key-prefix and return a List + bcrypt-match each candidate so a prefix collision degrades gracefully instead of throwing NonUniqueResultException. (5) Apply an IP/source-based rate limit to /internal/webhooks/** and reconsider the global Valkey fail-open for money-affecting POSTs (degrade to a conservative local limit + alert).

### Refuted / already-mitigated (do NOT re-flag)

The following were refuted or are already mitigated — do NOT re-flag:

1) 3DS challenge result accepted from any origin = "cardholder authentication / SCA bypass" — REFUTED as an auth bypass. The missing event.origin check is real (challenge-handler.ts:139-155) but carries no auth impact: useConfirmPayment.ts:50-52 reacts to 'succeeded' only by re-calling nexuspay.confirm(paymentTokenId), which POSTs ONLY {payment_token_id} to /v1/checkout/confirm (ConfirmSessionRequest.java:7-10) — no 3DS status/CAVV/ECI/cres is ever sent; CheckoutController.java:93-120 decides on server authority. Worst real impact is a same-page UI dismiss/spoof (checkout DoS). Captured as defense-in-depth hardening only in B-021. Loose postMessage posture already logged/accepted in .perpetua/security/AUDITS.md:107-114 and ratchets.json line 9.

2) Payout execution to the external rail carries no idempotency key => double-pay — the idempotency/transaction-boundary gap is real but NOT presently reachable to money loss: the only PayoutExecutionPort impl is PayoutExecutionStubAdapter (no-op stub, GAP-062), and the scheduler is off by default (PayoutScheduler.java:20 @ConditionalOnProperty). Latent design risk, folded into B-011 as a precondition that MUST land before a real disbursement rail (GAP-062) is wired. Not a separately-flaggable present defect.

3) HyperSwitch adapter forwards caller Idempotency-Key without CRLF validation (header injection) — REFUTED/NOT REACHABLE. The only client ingress is an inbound HTTP/1.1 header (@RequestHeader). Embedded Tomcat parses/rejects CR/LF before request.getHeader() returns, so the value cannot contain a raw CR/LF; no request-body field feeds these keys. Even though the outbound stack is SimpleClientHttpRequestFactory (not java.net.http.HttpClient), there is no attacker-reachable CRLF source. Servlet-container header parse is the effective guard. A boundary regex is optional hardening, not a fix for a reachable bug.

4) DeadLetterReprocessor non-owner-checked lock release / TTL expiry => two reprocessors double-republish => duplicate ledger postings — REFUTED as to the double-ledger impact. The unconditional redisTemplate.delete(LOCK_KEY) (line 78) is a real defensiveness inconsistency vs SchedulerLock's RELEASE_IF_OWNER Lua (worth a minor hardening fix), BUT the exploit chain is defeated: the Kafka send is async (.whenComplete, no .get) so the batch cannot exceed the 2m TTL under broker latency; retryEntry commits status=RETRYING synchronously per-entry BEFORE the send and findRetryable selects PENDING only, so a second reprocessor cannot re-select the same rows; and any residual duplicate is absorbed by the ledger consumer's idempotency check. The genuinely useful residual (no UNIQUE on journal_entries.payment_reference) is captured in B-010. (The separate, REAL lost-RETRYING-transition defect in this same file IS captured as B-016.)

5) Ledger journal-entry idempotency check-then-act => concurrent double-posting that double-moves balances — REFUTED as to the balance double-move. The check-then-act and missing UNIQUE are real (captured in B-010 as fail-closed hardening), but a concurrent double-MOVE cannot commit: both writers target deterministic per-currency account rows and the balance UPDATE is version-gated (WHERE id=? AND version=?) under Isolation.SERIALIZABLE, so the loser aborts (40001) or matches 0 rows and rolls back its entire entry; the Kafka listener factory defaults to concurrency=1 and outbox keys by aggregate_id (one partition/thread per payment). B-010 is correctly scoped as a defense-in-depth fail-closed invariant, NOT a presently-reachable balance-corruption bug.

6) HyperSwitch webhook no timestamp/nonce replay protection + Valkey dedup fails open => duplicate capture/refund outbox event — REFUTED as to the money double-move. A durable, permanent, fail-CLOSED Postgres unique constraint on inbound_webhooks.event_id (InboundWebhook.java:22; V1301:4) backstops all three failure modes (24h boundary / Valkey down / Valkey flushed): on replay the duplicate save raises DataIntegrityViolationException (uncaught — the only catch handles JsonProcessingException), rolling back the WHOLE @Transactional method including the outbox insert, so no duplicate money-affecting event commits. L-007 constant-time HMAC intact. The missing timestamp/nonce is hardening only. NOTE: this is the REPLAY angle; the DISTINCT, real ordering bug on this same controller (Valkey SET-NX before commit suppressing a rolled-back webhook's redelivery) IS a confirmed defect and is captured as B-015 — do not conflate the two.

Also do not re-report the known guards cited throughout: B-004 StartupSecretsValidator (default-secret fail-closed), B-002 RLS dormancy by design, B-024 GatedPaymentGateway PSP screening, B-009 deterministic refund-approval key, L-007 HMAC, L-008 evidence path-traversal guard, L-009 keyed-HMAC PAN fingerprint, and the green semgrep/gitleaks/OSV gates. PR #3 (fraud request-fingerprint) and PR #4 (refund reconciler) are in-flight and not on this tree.
