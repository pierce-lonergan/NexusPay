#!/usr/bin/env bash
# =============================================================================
# perpetua-loop.sh (v2) — supervisor harness for the PERPETUA autonomous agent
#
# The agent (Claude Code session) is mortal; this script is the immortality
# layer. It: holds a lock, honors the STOP kill switch and the agent-written
# runtime/next-wake timestamp, invokes `claude -p` headlessly, detects
# usage-limit / transient failures, sleeps until reset (with backoff), and
# respawns forever.
#
# v2: injects a [budget hint: ...] into each session prompt — sessions started
# in the trailing 5-hour window + window age — so the agent can right-size its
# iteration count (PERPETUA.md §9.1). Enrich via PERPETUA_BUDGET_CMD (any
# command printing one line, e.g. a ccusage-style local estimator).
#
# Modes:
#   ./perpetua-loop.sh            # daemon mode: loop forever (systemd/launchd)
#   ./perpetua-loop.sh --once     # cron mode: run at most one session, exit
#
# Env config (all optional):
#   PERPETUA_REPO       repo root            (default: directory of this script)
#   PERPETUA_MODEL      model id             (default: account default)
#   PERPETUA_YOLO=1     fully unattended via --dangerously-skip-permissions
#                       (ONLY inside a container/VM/dedicated user — §18.4)
#   PERPETUA_TOOLS      --allowedTools value when YOLO is off
#   PERPETUA_BUDGET_CMD command whose 1-line stdout is appended to the hint
#   PERPETUA_MIN_GAP    min seconds between session starts   (default 300)
#   PERPETUA_MAX_LOG_DAYS  log retention in days             (default 14)
# =============================================================================
set -uo pipefail   # NOT -e: claude failures are expected, handled events

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="${PERPETUA_REPO:-$SCRIPT_DIR}"
STATE_DIR="$REPO_DIR/.perpetua"
RUN_DIR="$STATE_DIR/runtime"
LOG_DIR="$RUN_DIR/logs"
LOCK_FILE="$RUN_DIR/lock"
STOP_FILE="$STATE_DIR/STOP"
WAKE_FILE="$RUN_DIR/next-wake"
HIST_FILE="$RUN_DIR/session-history"        # one session-start epoch per line

MODEL="${PERPETUA_MODEL:-}"
YOLO="${PERPETUA_YOLO:-0}"
ALLOWED_TOOLS="${PERPETUA_TOOLS:-Read,Edit,Write,Glob,Grep,WebSearch,WebFetch,Task,Bash(git *),Bash(gh *),Bash(npm *),Bash(pytest*),Bash(python *),Bash(make *)}"
BUDGET_CMD="${PERPETUA_BUDGET_CMD:-}"
MIN_GAP="${PERPETUA_MIN_GAP:-300}"
MAX_LOG_DAYS="${PERPETUA_MAX_LOG_DAYS:-14}"

WINDOW_SECS=18000                           # 5-hour rolling window
BACKOFF_TRANSIENT_START=900                 # 15 min
BACKOFF_MAX=18000                           # 5 h ceiling
BACKOFF_LIMIT_DEFAULT=5400                  # 90 min if reset time unparseable
FAIL_STREAK_ALERT=8

ONCE=0; [[ "${1:-}" == "--once" ]] && ONCE=1

BASE_PROMPT='PERPETUA session start. Follow the operating core in CLAUDE.md exactly, beginning with .perpetua/HANDOFF.md. Checkpoint before exiting.'

mkdir -p "$LOG_DIR"

log() { printf '%s [loop] %s\n' "$(date -Is)" "$*" | tee -a "$LOG_DIR/$(date +%F).log" >&2; }
alert() { printf '%s ALERT %s\n' "$(date -Is)" "$*" >> "$RUN_DIR/alerts.log"; log "ALERT: $*"; }

# ---------- lock (stale-PID aware) -------------------------------------------
acquire_lock() {
  if [[ -f "$LOCK_FILE" ]]; then
    local pid; pid="$(cat "$LOCK_FILE" 2>/dev/null || true)"
    if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
      return 1
    fi
    log "stale lock (pid ${pid:-?}) — reclaiming"
  fi
  echo $$ > "$LOCK_FILE"
}
release_lock() { [[ "$(cat "$LOCK_FILE" 2>/dev/null)" == "$$" ]] && rm -f "$LOCK_FILE"; }
trap 'release_lock' EXIT INT TERM

# ---------- gates -------------------------------------------------------------
stopped() { [[ -f "$STOP_FILE" ]]; }

wake_remaining() {                             # seconds until wake (0 if due)
  [[ -f "$WAKE_FILE" ]] || { echo 0; return; }
  local t now; t="$(tr -dc '0-9' < "$WAKE_FILE")"; now="$(date +%s)"
  [[ -z "$t" ]] && { echo 0; return; }
  (( t > now )) && echo $(( t - now )) || echo 0
}

# ---------- budget hint (v2) ---------------------------------------------------
prune_history() {                              # keep only entries in the window
  [[ -f "$HIST_FILE" ]] || return 0
  local now cutoff tmp; now="$(date +%s)"; cutoff=$(( now - WINDOW_SECS ))
  tmp="$(mktemp)"; awk -v c="$cutoff" '$1 >= c' "$HIST_FILE" > "$tmp" && mv "$tmp" "$HIST_FILE"
}

budget_hint() {                                # echoes the hint string
  prune_history
  local now n oldest age_min hint extra
  now="$(date +%s)"
  n=0; [[ -f "$HIST_FILE" ]] && n="$(wc -l < "$HIST_FILE" | tr -d ' ')"
  if (( n > 0 )); then
    oldest="$(head -1 "$HIST_FILE")"; age_min=$(( (now - oldest) / 60 ))
    hint="session #$((n+1)) in current 5h window, window opened ~${age_min}m ago"
  else
    hint="session #1, fresh 5h window"
  fi
  if [[ -n "$BUDGET_CMD" ]]; then
    extra="$(bash -c "$BUDGET_CMD" 2>/dev/null | head -1 | tr -d '\n' || true)"
    [[ -n "$extra" ]] && hint="$hint; $extra"
  fi
  echo "$hint"
}

# ---------- limit detection ----------------------------------------------------
output_hit_limit() { grep -Eqi 'usage limit|limit reached|rate.?limit|resets at|hour limit|weekly limit|overloaded|529' "$1"; }

sleep_for_limit() {
  local remaining; remaining="$(wake_remaining)"
  if (( remaining > 0 )); then
    log "limit hit — agent scheduled wake in ${remaining}s"
  else
    remaining=$BACKOFF_LIMIT_DEFAULT
    log "limit hit — no parseable wake time, sleeping default ${remaining}s"
  fi
  (( remaining > BACKOFF_MAX )) && remaining=$BACKOFF_MAX   # re-check ≤ every 5h
  do_wait "$remaining"
}

# ---------- waiting (mode-aware) ----------------------------------------------
do_wait() {
  local secs="$1"
  if (( ONCE )); then log "(--once) due in ${secs}s — exiting, cron will retry"; exit 0; fi
  sleep "$secs"
}

# ---------- one session --------------------------------------------------------
run_session() {
  local out="$LOG_DIR/session-$(date +%F-%H%M%S).log"
  local hint prompt
  hint="$(budget_hint)"
  prompt="$BASE_PROMPT [budget hint: $hint]"
  date +%s >> "$HIST_FILE"

  local -a cmd=(claude -p "$prompt" --output-format json)
  [[ -n "$MODEL" ]] && cmd+=(--model "$MODEL")
  if [[ "$YOLO" == "1" ]]; then cmd+=(--dangerously-skip-permissions)
  else cmd+=(--permission-mode acceptEdits --allowedTools "$ALLOWED_TOOLS"); fi

  log "session start ($hint) → ${out##*/}"
  ( cd "$REPO_DIR" && "${cmd[@]}" ) >"$out" 2>&1
  local rc=$?

  grep -m1 '^PERPETUA:' "$out" 2>/dev/null | while read -r l; do log "$l"; done
  if command -v jq >/dev/null 2>&1; then
    jq -r 'select(.result?) | "result: " + (.result|tostring|.[0:200])' "$out" 2>/dev/null | tail -1 | while read -r l; do log "$l"; done
  fi
  log "session end rc=$rc"

  if (( rc == 0 )) && ! output_hit_limit "$out"; then return 0; fi
  if output_hit_limit "$out" || (( $(wake_remaining) > 0 )); then return 2; fi   # limit
  return 1                                                                        # transient
}

rotate_logs() { find "$LOG_DIR" -type f -mtime +"$MAX_LOG_DAYS" -delete 2>/dev/null || true; }

# ================================ main =========================================
command -v claude >/dev/null 2>&1 || { alert "claude CLI not found in PATH"; exit 127; }
[[ -d "$REPO_DIR/.git" ]] || { alert "$REPO_DIR is not a git repo"; exit 1; }

if ! acquire_lock; then log "another supervisor holds the lock — exiting"; exit 0; fi

backoff=$BACKOFF_TRANSIENT_START
fail_streak=0

while :; do
  rotate_logs
  if stopped; then
    log "STOP file present — halted (delete .perpetua/STOP to resume)"
    (( ONCE )) && exit 0
    sleep 300; continue
  fi
  rem="$(wake_remaining)"
  if (( rem > 0 )); then
    log "next-wake in ${rem}s — waiting"
    do_wait "$rem"; continue
  fi

  start=$(date +%s)
  run_session; status=$?
  case $status in
    0)  fail_streak=0; backoff=$BACKOFF_TRANSIENT_START
        rm -f "$WAKE_FILE"
        ;;
    2)  fail_streak=0
        sleep_for_limit
        rm -f "$WAKE_FILE"          # consumed; agent rewrites if still limited
        ;;
    1)  fail_streak=$(( fail_streak + 1 ))
        log "transient failure (#$fail_streak) — backoff ${backoff}s"
        (( fail_streak >= FAIL_STREAK_ALERT )) && alert "$fail_streak consecutive failures — check auth/network/logs"
        do_wait "$backoff"
        backoff=$(( backoff * 2 )); (( backoff > BACKOFF_MAX )) && backoff=$BACKOFF_MAX
        ;;
  esac

  (( ONCE )) && { log "(--once) cycle complete — exiting"; exit 0; }

  elapsed=$(( $(date +%s) - start ))
  (( elapsed < MIN_GAP )) && sleep $(( MIN_GAP - elapsed ))
done
