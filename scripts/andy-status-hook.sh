#!/bin/sh
# Andy-managed status hook — do not edit.
# Installed to ~/.andy/bin/andy-status-hook.sh by the Andy desktop app, andyd, or install-andy.sh.
# Usage: andy-status-hook.sh <working|done|blocked|error> [respond]
# respond: none (default) | empty | allow | stop
#
# Resolves the active task via $ANDY_PROJECT_ROOT/.andy/active-task (default: $PWD).
# No-ops when the pointer is missing so user-level / shared hooks are safe.
status="${1:-done}"
respond="${2:-none}"
ROOT="${ANDY_PROJECT_ROOT:-$PWD}"
ACTIVE="$ROOT/.andy/active-task"
if [ ! -f "$ACTIVE" ]; then
  case "$respond" in
    empty) printf '%s\n' '{}' ;;
    allow) printf '%s\n' '{"decision":"allow"}' ;;
    stop) printf '%s\n' '{"decision":"stop"}' ;;
  esac
  exit 0
fi
task_id=$(tr -d '[:space:]' < "$ACTIVE")
if [ -z "$task_id" ]; then
  case "$respond" in
    empty) printf '%s\n' '{}' ;;
    allow) printf '%s\n' '{"decision":"allow"}' ;;
    stop) printf '%s\n' '{"decision":"stop"}' ;;
  esac
  exit 0
fi
dir="$ROOT/.andy/$task_id"
mkdir -p "$dir"
printf '{"status":"%s","at":%s}\n' "$status" "$(date +%s)" >> "$dir/status.json"
case "$respond" in
  empty) printf '%s\n' '{}' ;;
  allow) printf '%s\n' '{"decision":"allow"}' ;;
  stop) printf '%s\n' '{"decision":"stop"}' ;;
esac
exit 0
