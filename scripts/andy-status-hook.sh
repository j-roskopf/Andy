#!/bin/sh
# Andy-managed status hook — do not edit.
# Installed to ~/.andy/bin/andy-status-hook.sh by the Andy desktop app, andyd, or install-andy.sh.
# Usage: andy-status-hook.sh <working|done|blocked|error> [respond] [gate]
# respond: none (default) | empty | allow | stop
# gate: none (default) | fully-idle | completed
#
# Resolves the active task via $ANDY_TASK_ID when set (per-session), else
# $ANDY_PROJECT_ROOT/.andy/active-task (default: $PWD/.andy/active-task).
# No-ops when neither is available so user-level / shared hooks are safe.
# Always consumes stdin (vendor hooks send JSON payloads).
status="${1:-done}"
respond="${2:-none}"
gate="${3:-none}"
payload=$(cat 2>/dev/null || true)

respond_and_exit() {
  case "$respond" in
    empty) printf '%s\n' '{}' ;;
    allow) printf '%s\n' '{"decision":"allow"}' ;;
    stop) printf '%s\n' '{"decision":"stop"}' ;;
  esac
  exit 0
}

case "$gate" in
  fully-idle)
    # Antigravity Stop: only record done when the turn is fully idle.
    printf '%s' "$payload" | grep -Eq '"fullyIdle"[[:space:]]*:[[:space:]]*true' || respond_and_exit
    ;;
  completed)
    # Cursor stop: only record done for a clean/aborted finish.
    printf '%s' "$payload" | grep -Eq '"status"[[:space:]]*:[[:space:]]*"(completed|aborted)"' || respond_and_exit
    ;;
esac

ROOT="${ANDY_PROJECT_ROOT:-$PWD}"
task_id=""
if [ -n "${ANDY_TASK_ID:-}" ]; then
  task_id=$(printf '%s' "$ANDY_TASK_ID" | tr -d '[:space:]')
else
  ACTIVE="$ROOT/.andy/active-task"
  if [ -f "$ACTIVE" ]; then
    task_id=$(tr -d '[:space:]' < "$ACTIVE")
  fi
fi
if [ -z "$task_id" ]; then
  respond_and_exit
fi
dir="$ROOT/.andy/$task_id"
mkdir -p "$dir"
printf '{"status":"%s","at":%s}\n' "$status" "$(date +%s)" >> "$dir/status.json"
respond_and_exit
