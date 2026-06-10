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

NEXT DEEP AUDIT should: run gitleaks over history, OSV-scan all Gradle deps,
semgrep java rulesets, and re-verify the OPEN-HIGH items above are closed or
still tracked. Log results here.
