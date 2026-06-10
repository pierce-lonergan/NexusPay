# QUESTIONS for the human (answer inline; processed at next BOOT)

## BLOCKING

### Q-001 | Ratify CHARTER.md and set autonomy level
PERPETUA is bootstrapped and operating at L1 (branch `perpetua/bootstrap`, local
commits only — no push, no PR, no merge to main). Please confirm:
- Mission/goals/targets in CHARTER.md correct?
- Autonomy level: stay **L1** (I open PRs you merge) / go **L2** (I merge to
  main when all gates pass, except T3 which always go via PR) / **L0** (advice
  only)?
- May I **push** branch `perpetua/bootstrap` and **open a PR** to your GitHub
  (`pierce-lonergan/NexusPay`)? (Default until you answer: NO — local only.)
**ANSWER:**

### Q-002 | Branch protection on main
PERPETUA's external-enforcement model (§18.3) wants `main` protected: require the
CI workflow to pass, forbid force-push. This is the structural backstop against a
future degenerated session. Will you enable branch protection (or want me to via
an L3 whitelist)?
**ANSWER:**

## FYI / lower priority

### Q-005 | (FYI, resolved-by-default) Token-aware pacing is wired
B-019 added adaptive pacing so the harness fills each 5h window with productive
Fable-5 work. To use it: (1) `npm i -g ccusage` (or it falls back to neutral
pacing); (2) run the loop with `PERPETUA_MODEL=claude-fable-5`; optionally set
`PERPETUA_TOKEN_BUDGET=<tokens>` if you know your plan's per-window number (else
ccusage infers it from your historical max). Tune `PACE_CAP_PCT` (default 90) /
`PACE_BAND_PCT` (default 10). No answer needed unless you want different defaults.

### Q-003 | Tooling install for always-on scanners
The quality ratchets need tools the repo doesn't yet have: JaCoCo (coverage),
PIT (mutation), gitleaks/OSV/semgrep (security), JMH (bench). I can add the
Gradle plugins (B-005) and CI steps (B-006/B-012) myself; the CLI scanners
(gitleaks/semgrep/osv-scanner) need to be installed on the runner/your machine.
Any preference on which, or should I default to the recommendations in
ratchets.json → tooling?
**ANSWER:**

### Q-004 | Dev-stack verification environment
Several HIGH items (RLS B-002, Flyway collisions B-011) can only be *verified*
against a running Postgres/Kafka/Valkey (Docker). This sandbox has no Docker. Is
there a CI job or environment where integration tests + a real `bootRun` can run
so these can be closed with evidence rather than reasoning?
**ANSWER:**
