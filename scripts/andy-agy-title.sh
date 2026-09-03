#!/bin/sh
# Andy-managed Antigravity title — do not edit.
# Installed to ~/.andy/bin/andy-agy-title.sh by the Andy desktop app, andyd, or install-andy.sh.
#
# Wired as ~/.gemini/antigravity-cli/settings.json → title.command.
# agy pipes the same JSON payload used by statusLine (agent_state, tool_confirmation_pending, …)
# on stdin; we write .andy/<taskId>/status.json and print a window title with andy:* markers
# that AgentStatusTracker scrapes via OSC / pane title.
payload=$(cat 2>/dev/null || true)

state=$(printf '%s' "$payload" | grep -o '"agent_state"[[:space:]]*:[[:space:]]*"[^"]*"' | head -1 | sed 's/.*"\([^"]*\)"$/\1/')
pending=0
if printf '%s' "$payload" | grep -Eq '"tool_confirmation_pending"[[:space:]]*:[[:space:]]*true'; then
  pending=1
fi

if [ "$pending" -eq 1 ]; then
  status=blocked
  marker=andy:blocked
else
  case "$state" in
    idle)
      status=done
      marker=andy:idle
      ;;
    thinking|working|tool_use)
      status=working
      marker=andy:working
      ;;
    *)
      status=""
      marker=""
      ;;
  esac
fi

HOOK="${HOME}/.andy/bin/andy-status-hook.sh"
if [ -x "$HOOK" ] && [ -n "$status" ]; then
  # Status is the argv; stdin gates are unused here (payload already consumed).
  printf '' | "$HOOK" "$status" >/dev/null 2>&1 || true
fi

# Keep the title short; markers must stay literal for OSC scrape.
if [ -n "$marker" ]; then
  printf 'agy %s\n' "$marker"
else
  printf 'agy\n'
fi
