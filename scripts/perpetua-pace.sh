#!/usr/bin/env bash
# =============================================================================
# perpetua-pace.sh — pure token-budget pacing controller (B-019 / ADR-007)
#
# Decides how aggressively the supervisor should run sessions so that the
# account's per-5h-window token budget is spent STEADILY across the window:
# never idling with budget unspent when the window rolls (wasted quota), never
# slamming the cap early and then sitting blocked.
#
# Pure functions only — no I/O, no globals mutated. Sourced by perpetua-loop.sh
# and exercised directly by scripts/test-perpetua-pacing.sh. All integer math
# (token counts fit comfortably in 63-bit).
#
# Model: a steady "pace line" rises from 0 at window start to B at window end.
#   target(t) = B * t_elapsed / T_window
#   ahead     = U - target
# Decision (deadband ±PACE_BAND_PCT of B, back off at PACE_CAP_PCT of B):
#   U >= cap%·B            -> BLOCKED    (stop; wake when the window frees)
#   ahead >  band·B        -> COOLDOWN   (burning too fast; sleep until the line catches U)
#   ahead < -band·B        -> AGGRESSIVE (under-using; run back-to-back + deeper sessions)
#   else                   -> STEADY     (on the line; normal gap)
# =============================================================================

# pace_decision U B t_elapsed T_window  ->  echoes "DECISION SLEEP_SECONDS"
# SLEEP_SECONDS: AGGRESSIVE=0, STEADY=-1 (caller uses its default gap),
#                COOLDOWN=seconds until the pace line reaches U (capped at time left),
#                BLOCKED=seconds until the window end (+buffer).
pace_decision() {
  local U="${1:-0}" B="${2:-0}" te="${3:-0}" T="${4:-18000}"
  local cap="${PACE_CAP_PCT:-90}" band="${PACE_BAND_PCT:-10}"

  # No budget knowledge (no ccusage / no history / override unset) -> neutral.
  if [ "$B" -le 0 ] || [ "$T" -le 0 ]; then echo "STEADY -1"; return 0; fi
  (( te < 0 )) && te=0
  (( te > T )) && te=$T
  local t_left=$(( T - te ))

  # Near the cap: stop starting sessions; wake when the window rolls.
  local cap_abs=$(( B * cap / 100 ))
  if (( U >= cap_abs )); then echo "BLOCKED $(( t_left + 120 ))"; return 0; fi

  local target=$(( B * te / T ))
  local band_abs=$(( B * band / 100 ))

  if (( U > target + band_abs )); then
    # Burning ahead of the line — sleep until the line reaches U.
    local t_target=$(( U * T / B ))
    local sleep=$(( t_target - te ))
    (( sleep < 0 )) && sleep=0
    (( sleep > t_left )) && sleep=$t_left
    echo "COOLDOWN $sleep"; return 0
  fi

  if (( U < target - band_abs )); then echo "AGGRESSIVE 0"; return 0; fi

  echo "STEADY -1"
}

# rigor_for DECISION -> a one-word depth hint the agent reads from the prompt to
# scale how much PRODUCTIVE work (reviewers, audit breadth, research, mutation)
# it does this session. Never "make-work" — just lean vs maximal rigor.
rigor_for() {
  case "${1:-STEADY}" in
    AGGRESSIVE) echo "MAX" ;;     # deep audits, multi-reviewer, research, mutation; more iterations
    STEADY)     echo "NORMAL" ;;
    COOLDOWN)   echo "LEAN" ;;    # one small item or groom; conserve
    BLOCKED)    echo "PAUSE" ;;
    *)          echo "NORMAL" ;;
  esac
}
