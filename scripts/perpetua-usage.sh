#!/usr/bin/env bash
# =============================================================================
# perpetua-usage.sh — token-budget estimator for the PERPETUA harness (B-019)
#
# Reads the current Claude 5-hour usage window via `ccusage` (local JSONL; no
# network needed) and emits:
#   - stdout: a one-line human budget hint (used as PERPETUA_BUDGET_CMD output)
#   - $RUN_DIR/budget-state: machine-readable KEY=VALUE lines the loop reads to
#     pace itself (U, B, T_ELAPSED, T_WINDOW, PROJECTED, DECISION-ready inputs)
#
# Budget B (per-window token ceiling) is, in priority order:
#   1. $PERPETUA_TOKEN_BUDGET  (explicit override, if you know your plan's number)
#   2. historical max completed-block tokens reported by ccusage (its own proxy)
#   3. unknown (0) -> the loop paces neutrally (STEADY) on time alone
#
# Degrades gracefully: missing ccusage or jq -> prints a hint saying so and
# writes B=0 (neutral). Never fails the caller.
# =============================================================================
set -uo pipefail

RUN_DIR="${PERPETUA_RUN_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/.perpetua/runtime}"
STATE_FILE="$RUN_DIR/budget-state"
T_WINDOW="${PERPETUA_WINDOW_SECS:-18000}"   # 5h
mkdir -p "$RUN_DIR"

emit() {  # DECISION-inputs -> state file + human hint
  local U="$1" B="$2" TE="$3" PROJ="$4" hint="$5"
  {
    echo "U=$U"
    echo "B=$B"
    echo "T_ELAPSED=$TE"
    echo "T_WINDOW=$T_WINDOW"
    echo "PROJECTED=$PROJ"
    echo "GENERATED=$(date +%s)"
  } > "$STATE_FILE"
  echo "$hint"
}

# Resolve a ccusage invocation: explicit override, global, or npx/bunx fallback.
ccusage_cmd() {
  if [ -n "${PERPETUA_CCUSAGE_CMD:-}" ]; then echo "$PERPETUA_CCUSAGE_CMD"; return 0; fi
  if command -v ccusage >/dev/null 2>&1; then echo "ccusage"; return 0; fi
  if command -v bunx   >/dev/null 2>&1; then echo "bunx ccusage"; return 0; fi
  if command -v npx    >/dev/null 2>&1; then echo "npx -y ccusage@latest"; return 0; fi
  return 1
}

CCU="$(ccusage_cmd || true)"
if [ -z "${CCU:-}" ] || ! command -v jq >/dev/null 2>&1; then
  emit 0 0 0 0 "budget: ccusage/jq unavailable — pacing STEADY (time-only). Install: npm i -g ccusage"
  exit 0
fi

now="$(date +%s)"

# Active block (tokens used + window start/end). Empty {"blocks":[]} if idle.
active="$( $CCU blocks --active --offline --json 2>/dev/null || true )"
U=0; start_epoch=0; proj=0
if [ -n "$active" ]; then
  read -r U start_epoch proj < <(
    printf '%s' "$active" | jq -r '
      (.blocks[]? | select(.isActive)) as $b
      | "\($b.totalTokens // 0) "
      + "\((($b.startTime // "") | sub("\\.[0-9]+";"") | (try fromdateiso8601 catch 0))) "
      + "\($b.projection.totalTokens // 0)"' 2>/dev/null | head -1
  )
  U="${U:-0}"; start_epoch="${start_epoch:-0}"; proj="${proj:-0}"
fi

# Budget ceiling B.
B="${PERPETUA_TOKEN_BUDGET:-0}"
if [ "$B" -le 0 ] 2>/dev/null; then
  # Historical max over completed (non-active, non-gap) blocks — ccusage's own proxy.
  allb="$( $CCU blocks --offline --json 2>/dev/null || true )"
  if [ -n "$allb" ]; then
    B="$( printf '%s' "$allb" | jq -r '[.blocks[]? | select((.isActive|not) and (.isGap|not)) | .totalTokens] | (max // 0)' 2>/dev/null )"
  fi
  B="${B:-0}"
fi

# Elapsed in the window.
TE=0
if [ "${start_epoch:-0}" -gt 0 ] 2>/dev/null; then TE=$(( now - start_epoch )); fi
(( TE < 0 )) && TE=0

# Human hint.
if [ "$B" -gt 0 ] 2>/dev/null; then
  pct=$(( U * 100 / B )); elapsed_pct=$(( TE * 100 / T_WINDOW ))
  hint="budget: ${U} tok used (~${pct}% of ~${B} cap) · window ${elapsed_pct}% elapsed · projected ${proj}"
else
  hint="budget: ${U} tok used this window · cap unknown (set PERPETUA_TOKEN_BUDGET or build ccusage history)"
fi

emit "$U" "$B" "$TE" "$proj" "$hint"
exit 0
