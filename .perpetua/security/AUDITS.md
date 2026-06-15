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
