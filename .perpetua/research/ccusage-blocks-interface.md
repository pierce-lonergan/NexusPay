# Research: ccusage `blocks` interface (for token-aware pacing)

- ccusage reads Claude's local JSONL (`~/.claude/projects/**/*.jsonl`) — fully
  local; `--offline` skips the optional pricing fetch. Token/window math needs no network.
- `blocks --active --offline --json` → `{"blocks":[ {…} ]}` (nested; empty `[]` if no
  active block). Active block fields (v20, Rust-backed): `isActive`, `startTime`,
  `endTime` (= start + 5h), `totalTokens`, `tokenCounts.{input,output,cacheCreation,cacheRead}Tokens`,
  `projection.{totalTokens,remainingMinutes}`, `burnRate.tokensPerMinute` (per MINUTE),
  `tokenLimitStatus.{limit,percentUsed,status}` (only with `--token-limit`).
- GOTCHA: `--token-limit max` + `--active` = no-op (max is computed over NON-active blocks,
  which the active filter empties → 0). So derive the cap separately: run `blocks --offline
  --json` (no --active) and take `max(.blocks[]|select(.isActive==false and .isGap==false)|.totalTokens)`.
- ISO timestamps carry milliseconds + `Z`; strip `.\d+` before `fromdateiso8601`.

3-bullet distillation:
1. Cap (B) proxy = historical max completed-block tokens (or `PERPETUA_TOKEN_BUDGET` override).
2. Active block gives tokens-used (U) + start/end → elapsed/remaining in the 5h window.
3. Degrade gracefully: no ccusage / no jq / no active block → neutral STEADY pacing.

Confidence: HIGH (source-read at v20.0.9, 2026-06-10). Expiry: 90 days (fast-moving tool;
re-verify JSON shape — it changed materially when ccusage went Rust). Consequence: wired into
scripts/perpetua-usage.sh + perpetua-pace.sh (B-019 / ADR-007).
