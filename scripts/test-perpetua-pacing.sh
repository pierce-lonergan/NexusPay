#!/usr/bin/env bash
# Unit tests for the pure pacing controller (scripts/perpetua-pace.sh).
# No ccusage / no network needed — feeds synthetic (U, B, elapsed) and asserts
# the decision + sleep. Run: bash scripts/test-perpetua-pacing.sh
set -uo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$DIR/perpetua-pace.sh"

PASS=0; FAIL=0
# default deadband/cap for deterministic tests
export PACE_BAND_PCT=10 PACE_CAP_PCT=90

check() { # desc expected_decision expected_sleep  U B te T
  local desc="$1" exp_d="$2" exp_s="$3"; shift 3
  local out; out="$(pace_decision "$@")"
  local d s; read -r d s <<<"$out"
  if [ "$d" = "$exp_d" ] && [ "$s" = "$exp_s" ]; then
    PASS=$((PASS+1)); printf 'ok   %-42s -> %s\n' "$desc" "$out"
  else
    FAIL=$((FAIL+1)); printf 'FAIL %-42s -> got [%s] want [%s %s]\n' "$desc" "$out" "$exp_d" "$exp_s"
  fi
}
check_word() { local desc="$1" got="$2" want="$3"; if [ "$got" = "$want" ]; then PASS=$((PASS+1)); printf 'ok   %-42s -> %s\n' "$desc" "$got"; else FAIL=$((FAIL+1)); printf 'FAIL %-42s -> got [%s] want [%s]\n' "$desc" "$got" "$want"; fi; }

T=18000
# 1. Behind the pace line at 50% elapsed -> run back-to-back.
check "behind-line=>AGGRESSIVE"  AGGRESSIVE 0     100000  2000000 9000 $T
# 2. Ahead of the line -> cool down until the line reaches U (13500s - 9000s = 4500s).
check "ahead-line=>COOLDOWN"     COOLDOWN   4500  1500000 2000000 9000 $T
# 3. At/над the cap (90%) -> stop, wake at window end + buffer.
check "near-cap=>BLOCKED"        BLOCKED    9120  1900000 2000000 9000 $T
# 4. Exactly on the line -> steady (caller uses its default gap).
check "on-line=>STEADY"          STEADY     -1    1000000 2000000 9000 $T
# 5. No budget knowledge -> neutral steady.
check "no-budget=>STEADY"        STEADY     -1    0       0       9000 $T
# 6. Fresh idle window (U=0, t=0) -> steady (rises to AGGRESSIVE once it falls behind).
check "fresh-window=>STEADY"     STEADY     -1    0       2000000 0    $T
# 7. Slightly behind but inside the deadband -> still STEADY (no oscillation).
check "within-deadband=>STEADY"  STEADY     -1    900000  2000000 9000 $T

# rigor dial
check_word "rigor AGGRESSIVE" "$(rigor_for AGGRESSIVE)" MAX
check_word "rigor STEADY"     "$(rigor_for STEADY)"     NORMAL
check_word "rigor COOLDOWN"   "$(rigor_for COOLDOWN)"   LEAN
check_word "rigor BLOCKED"    "$(rigor_for BLOCKED)"    PAUSE

echo "-----"
echo "pacing tests: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
